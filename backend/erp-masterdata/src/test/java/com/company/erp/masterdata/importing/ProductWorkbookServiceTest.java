package com.company.erp.masterdata.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ProductWorkbookServiceTest {
  private static final List<String> HEADERS = List.of(
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

  private final ProductWorkbookService workbooks = new ProductWorkbookService();

  @Test
  void createsTheCanonicalProductTemplate() throws Exception {
    try (var workbook = new XSSFWorkbook(
        new ByteArrayInputStream(workbooks.createTemplate()))) {
      var header = workbook.getSheetAt(0).getRow(0);

      assertThat(HEADERS)
          .containsExactlyElementsOf(
              java.util.stream.IntStream.range(0, HEADERS.size())
                  .mapToObj(index -> header.getCell(index).getStringCellValue())
                  .toList());
    }
  }

  @Test
  void preservesRowNumbersAndReportsDeterministicValidationErrors() throws Exception {
    var workbook = workbook(
        row("SPU-1", "智能水杯", "星链", "家居/水具", "SKU-1", "黑色", "6901", "{\"color\":\"黑色\"}", "件", "DRAFT"),
        row("SPU-2", "移动电源", "", "数码/电源", "", "白色", "6902", "{}", "件", "ACTIVE"),
        row("SPU-3", "扩展坞", "", "数码/配件", "SKU-3", "灰色", "6903", "{bad}", "件", "DRAFT"),
        row("SPU-4", "重复商品", "", "", "SKU-1", "另一个", "6904", "{}", "件", "DRAFT"),
        row("SPU-5", "条码冲突", "", "", "SKU-5", "冲突", "DB-BARCODE", "{}", "件", "DRAFT"),
        row("SPU-6", "状态错误", "", "", "SKU-6", "错误", "6906", "{}", "件", "ONLINE"));
    var existing = new ProductWorkbookService.ExistingProductIndex(
        Set.of(),
        Set.of(),
        Set.of("DB-BARCODE"));

    var result = workbooks.parseAndValidate(
        new ByteArrayInputStream(workbook),
        existing);

    assertThat(result.validRows()).hasSize(1);
    assertThat(result.createCount()).isEqualTo(1);
    assertThat(result.errors())
        .extracting(error -> error.sourceRowNumber() + ":" + error.code())
        .containsExactly(
            "3:REQUIRED_FIELD",
            "4:INVALID_JSON",
            "5:DUPLICATE_SKU",
            "6:DUPLICATE_BARCODE",
            "7:INVALID_STATUS");
  }

  @Test
  void classifiesExistingSpusAsUpdatesAndCreatesAnErrorWorkbook() throws Exception {
    var source = workbook(
        row("SPU-EXISTING", "已有商品", "", "", "SKU-EXISTING", "新规格", "6908", "{}", "件", "ACTIVE"),
        row("", "错误商品", "", "", "SKU-BAD", "错误", "6909", "{}", "件", "DRAFT"));
    var result = workbooks.parseAndValidate(
        new ByteArrayInputStream(source),
        new ProductWorkbookService.ExistingProductIndex(
            Set.of("SPU-EXISTING"),
            Set.of("SKU-EXISTING"),
            Set.of(),
            Map.of("SKU-EXISTING", "SPU-EXISTING")));

    assertThat(result.updateCount()).isEqualTo(1);
    assertThat(result.createCount()).isZero();
    assertThat(result.failureCount()).isEqualTo(1);

    try (var errorWorkbook = new XSSFWorkbook(
        new ByteArrayInputStream(workbooks.createErrorWorkbook(result)))) {
      var sheet = errorWorkbook.getSheetAt(0);
      assertThat(sheet.getRow(0).getCell(10).getStringCellValue()).isEqualTo("错误代码");
      assertThat(sheet.getRow(0).getCell(11).getStringCellValue()).isEqualTo("错误说明");
      assertThat(sheet.getRow(1).getCell(10).getStringCellValue()).isEqualTo("REQUIRED_FIELD");
    }
  }

  private static String[] row(String... values) {
    return values;
  }

  private static byte[] workbook(String[]... rows) throws Exception {
    try (var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("商品");
      var header = sheet.createRow(0);
      for (var index = 0; index < HEADERS.size(); index++) {
        header.createCell(index).setCellValue(HEADERS.get(index));
      }
      for (var rowIndex = 0; rowIndex < rows.length; rowIndex++) {
        var row = sheet.createRow(rowIndex + 1);
        for (var column = 0; column < rows[rowIndex].length; column++) {
          row.createCell(column).setCellValue(rows[rowIndex][column]);
        }
      }
      workbook.write(output);
      return output.toByteArray();
    }
  }
}
