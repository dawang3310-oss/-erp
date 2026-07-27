package com.company.erp.masterdata.importing;

import com.company.erp.masterdata.product.ProductStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ProductWorkbookService {
  public static final long MAX_WORKBOOK_BYTES = 20L * 1024 * 1024;
  public static final int MAX_DATA_ROWS = 10_000;
  public static final List<String> HEADERS = List.of(
      "SPU编码",
      "SPU名称",
      "品牌",
      "类目",
      "SKU编码",
      "SKU名称",
      "条码",
      "规格JSON",
      "计量单位",
      "商品状态");

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
  };

  public byte[] createTemplate() {
    try (var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("商品");
      sheet.createFreezePane(0, 1);
      var headerStyle = workbook.createCellStyle();
      headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
      headerStyle.setAlignment(HorizontalAlignment.CENTER);
      var textStyle = workbook.createCellStyle();
      textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
      var header = sheet.createRow(0);
      for (var index = 0; index < HEADERS.size(); index++) {
        var cell = header.createCell(index, CellType.STRING);
        cell.setCellValue(HEADERS.get(index));
        cell.setCellStyle(headerStyle);
        sheet.setDefaultColumnStyle(index, textStyle);
        sheet.setColumnWidth(index, index == 7 ? 7000 : 4500);
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot create product template", exception);
    }
  }

  public ProductPreflight parseAndValidate(
      InputStream workbookInput,
      ExistingProductIndex existing) {
    var bytes = readBounded(workbookInput);
    var index = existing == null ? ExistingProductIndex.empty() : existing;
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      var sheet = workbook.getSheetAt(0);
      requireHeaders(sheet.getRow(0));
      if (sheet.getLastRowNum() > MAX_DATA_ROWS) {
        return new ProductPreflight(
            List.of(),
            List.of(new ProductRowError(
                MAX_DATA_ROWS + 2,
                "ROW_LIMIT_EXCEEDED",
                "商品数据不能超过 " + MAX_DATA_ROWS + " 行",
                List.of())),
            0,
            0,
            0,
            0,
            1);
      }

      var valid = new ArrayList<ValidProductRow>();
      var errors = new ArrayList<ProductRowError>();
      var seenSkus = new HashSet<String>();
      var seenBarcodes = new HashSet<String>();
      var formatter = new DataFormatter(Locale.ROOT);
      for (var rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        var row = sheet.getRow(rowIndex);
        var values = values(row, formatter);
        if (values.stream().allMatch(String::isBlank)) {
          continue;
        }
        var sourceRow = rowIndex + 1;
        var error = validateRow(row, values, sourceRow, index, seenSkus, seenBarcodes);
        if (error != null) {
          errors.add(error);
          continue;
        }
        valid.add(toValidRow(values, sourceRow));
      }

      var validSpus = valid.stream().map(ValidProductRow::spuCode).distinct().toList();
      var updateCount = (int) validSpus.stream().filter(index.spuCodes()::contains).count();
      var createCount = validSpus.size() - updateCount;
      var conflictCount = (int) errors.stream()
          .filter(error -> error.code().startsWith("DUPLICATE_")
              || error.code().equals("CONFLICTING_SPU"))
          .count();
      return new ProductPreflight(
          valid,
          errors,
          createCount,
          updateCount,
          0,
          conflictCount,
          errors.size());
    } catch (IOException exception) {
      throw new IllegalArgumentException("INVALID_WORKBOOK: 无法读取 Excel 文件", exception);
    }
  }

  public byte[] createErrorWorkbook(ProductPreflight preflight) {
    try (var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("错误明细");
      var header = sheet.createRow(0);
      for (var index = 0; index < HEADERS.size(); index++) {
        header.createCell(index).setCellValue(HEADERS.get(index));
      }
      header.createCell(HEADERS.size()).setCellValue("错误代码");
      header.createCell(HEADERS.size() + 1).setCellValue("错误说明");
      for (var index = 0; index < preflight.errors().size(); index++) {
        var error = preflight.errors().get(index);
        var row = sheet.createRow(index + 1);
        for (var column = 0; column < error.originalValues().size(); column++) {
          row.createCell(column, CellType.STRING).setCellValue(error.originalValues().get(column));
        }
        row.createCell(HEADERS.size(), CellType.STRING).setCellValue(error.code());
        row.createCell(HEADERS.size() + 1, CellType.STRING).setCellValue(error.message());
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot create product error workbook", exception);
    }
  }

  public byte[] exportProducts(Stream<ProductExportRow> products) {
    try (products;
        var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("商品");
      var header = sheet.createRow(0);
      for (var index = 0; index < HEADERS.size(); index++) {
        header.createCell(index).setCellValue(HEADERS.get(index));
      }
      var rows = products.limit(MAX_DATA_ROWS + 1L).toList();
      if (rows.size() > MAX_DATA_ROWS) {
        throw new IllegalArgumentException("Export cannot exceed " + MAX_DATA_ROWS + " rows");
      }
      for (var index = 0; index < rows.size(); index++) {
        var row = sheet.createRow(index + 1);
        var values = rows.get(index).values();
        for (var column = 0; column < values.size(); column++) {
          row.createCell(column, CellType.STRING).setCellValue(values.get(column));
        }
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot export products", exception);
    }
  }

  private static ProductRowError validateRow(
      org.apache.poi.ss.usermodel.Row row,
      List<String> values,
      int sourceRow,
      ExistingProductIndex existing,
      Set<String> seenSkus,
      Set<String> seenBarcodes) {
    for (var column = 0; column < HEADERS.size(); column++) {
      var cell = row == null ? null : row.getCell(column);
      if (cell != null && cell.getCellType() == CellType.FORMULA) {
        return error(sourceRow, "INVALID_CELL_TYPE", "业务字段不允许使用公式", values);
      }
    }
    if (blank(values, 0) || blank(values, 1) || blank(values, 4)
        || blank(values, 5) || blank(values, 8) || blank(values, 9)) {
      return error(sourceRow, "REQUIRED_FIELD", "SPU、SKU、名称、单位和状态为必填项", values);
    }
    try {
      ProductStatus.valueOf(values.get(9));
    } catch (IllegalArgumentException exception) {
      return error(sourceRow, "INVALID_STATUS", "商品状态不合法: " + values.get(9), values);
    }
    try {
      JSON.readValue(values.get(7).isBlank() ? "{}" : values.get(7), STRING_MAP);
    } catch (Exception exception) {
      return error(sourceRow, "INVALID_JSON", "规格JSON必须是字符串键值对象", values);
    }
    var sku = values.get(4);
    var existingSkuSpu = existing.skuToSpu().get(sku);
    if (!seenSkus.add(sku)
        || (existing.skuCodes().contains(sku)
            && !values.get(0).equals(existingSkuSpu))) {
      return error(sourceRow, "DUPLICATE_SKU", "SKU编码重复: " + sku, values);
    }
    var barcode = values.get(6);
    var existingBarcodeSku = existing.barcodeToSku().get(barcode);
    if (!barcode.isBlank()
        && (!seenBarcodes.add(barcode)
            || (existing.barcodes().contains(barcode)
                && !sku.equals(existingBarcodeSku)))) {
      return error(sourceRow, "DUPLICATE_BARCODE", "条码重复: " + barcode, values);
    }
    return null;
  }

  private static ValidProductRow toValidRow(List<String> values, int sourceRow) {
    try {
      return new ValidProductRow(
          sourceRow,
          values.get(0),
          values.get(1),
          nullable(values.get(2)),
          nullable(values.get(3)),
          values.get(4),
          values.get(5),
          nullable(values.get(6)),
          JSON.readValue(values.get(7).isBlank() ? "{}" : values.get(7), STRING_MAP),
          values.get(8),
          ProductStatus.valueOf(values.get(9)),
          List.copyOf(values));
    } catch (IOException exception) {
      throw new IllegalStateException("Validated JSON could not be parsed", exception);
    }
  }

  private static void requireHeaders(org.apache.poi.ss.usermodel.Row header) {
    var formatter = new DataFormatter(Locale.ROOT);
    for (var index = 0; index < HEADERS.size(); index++) {
      var actual = header == null ? "" : formatter.formatCellValue(header.getCell(index)).trim();
      if (!HEADERS.get(index).equals(actual)) {
        throw new IllegalArgumentException(
            "INVALID_HEADER: 第 " + (index + 1) + " 列必须是 " + HEADERS.get(index));
      }
    }
  }

  private static List<String> values(
      org.apache.poi.ss.usermodel.Row row,
      DataFormatter formatter) {
    var result = new ArrayList<String>(HEADERS.size());
    for (var index = 0; index < HEADERS.size(); index++) {
      var cell = row == null ? null : row.getCell(index);
      result.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
    }
    return result;
  }

  private static byte[] readBounded(InputStream input) {
    if (input == null) {
      throw new IllegalArgumentException("workbook is required");
    }
    try {
      var bytes = input.readNBytes((int) MAX_WORKBOOK_BYTES + 1);
      if (bytes.length > MAX_WORKBOOK_BYTES) {
        throw new IllegalArgumentException("WORKBOOK_TOO_LARGE: Excel 文件不能超过 20 MiB");
      }
      return bytes;
    } catch (IOException exception) {
      throw new IllegalArgumentException("INVALID_WORKBOOK: 无法读取 Excel 文件", exception);
    }
  }

  private static boolean blank(List<String> values, int index) {
    return values.get(index).isBlank();
  }

  private static String nullable(String value) {
    return value.isBlank() ? null : value;
  }

  private static ProductRowError error(
      int sourceRow,
      String code,
      String message,
      List<String> values) {
    return new ProductRowError(sourceRow, code, message, List.copyOf(values));
  }

  public record ExistingProductIndex(
      Set<String> spuCodes,
      Set<String> skuCodes,
      Set<String> barcodes,
      Map<String, String> skuToSpu,
      Map<String, String> barcodeToSku) {
    public ExistingProductIndex {
      spuCodes = spuCodes == null ? Set.of() : Set.copyOf(spuCodes);
      skuCodes = skuCodes == null ? Set.of() : Set.copyOf(skuCodes);
      barcodes = barcodes == null ? Set.of() : Set.copyOf(barcodes);
      skuToSpu = skuToSpu == null ? Map.of() : Map.copyOf(skuToSpu);
      barcodeToSku = barcodeToSku == null ? Map.of() : Map.copyOf(barcodeToSku);
    }

    public ExistingProductIndex(
        Set<String> spuCodes,
        Set<String> skuCodes,
        Set<String> barcodes) {
      this(spuCodes, skuCodes, barcodes, Map.of(), Map.of());
    }

    public ExistingProductIndex(
        Set<String> spuCodes,
        Set<String> skuCodes,
        Set<String> barcodes,
        Map<String, String> skuToSpu) {
      this(spuCodes, skuCodes, barcodes, skuToSpu, Map.of());
    }

    public static ExistingProductIndex empty() {
      return new ExistingProductIndex(Set.of(), Set.of(), Set.of(), Map.of(), Map.of());
    }
  }

  public record ValidProductRow(
      int sourceRowNumber,
      String spuCode,
      String spuName,
      String brandName,
      String categoryPath,
      String skuCode,
      String skuName,
      String barcode,
      Map<String, String> specifications,
      String unit,
      ProductStatus status,
      List<String> originalValues) {
  }

  public record ProductRowError(
      int sourceRowNumber,
      String code,
      String message,
      List<String> originalValues) {
  }

  public record ProductPreflight(
      List<ValidProductRow> validRows,
      List<ProductRowError> errors,
      int createCount,
      int updateCount,
      int skipCount,
      int conflictCount,
      int failureCount) {
    public ProductPreflight {
      validRows = List.copyOf(validRows);
      errors = List.copyOf(errors);
    }
  }

  public record ProductExportRow(List<String> values) {
    public ProductExportRow {
      values = List.copyOf(values);
      if (values.size() != HEADERS.size()) {
        throw new IllegalArgumentException("Product export rows require " + HEADERS.size() + " values");
      }
    }
  }
}
