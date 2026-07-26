package com.company.erp.masterdata.importing;

import static com.company.erp.masterdata.product.ProductCommands.AuditActor;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.erp.masterdata.product.JdbcProductCatalogService;
import com.company.erp.masterdata.product.JdbcProductQueryService;
import com.company.erp.masterdata.product.ProductObjectStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class ProductImportServiceIT {
  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void preflightsWithoutWritingProductsAndExecutesConfirmationOnce() throws Exception {
    try (var mysql = new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName("erp")
        .withUsername("erp")
        .withPassword("erp")) {
      mysql.start();
      var dataSource = new DriverManagerDataSource(
          mysql.getJdbcUrl(),
          mysql.getUsername(),
          mysql.getPassword());
      Flyway.configure().dataSource(dataSource).load().migrate();
      var jdbc = new JdbcTemplate(dataSource);
      var transactions = new DataSourceTransactionManager(dataSource);
      var objects = new MemoryObjectStore();
      ProductImportService imports = new JdbcProductImportService(
          jdbc,
          transactions,
          objects,
          new ProductWorkbookService(),
          new JdbcProductCatalogService(jdbc, transactions),
          new JdbcProductQueryService(jdbc));
      var actor = new AuditActor("product-admin-1", Set.of("PRODUCT_ADMIN"));
      var workbook = workbookWithValidAndInvalidRows();

      var jobId = imports.createPreflightJob(
          new ByteArrayInputStream(workbook),
          workbook.length,
          "products.xlsx",
          actor);

      assertThat(imports.getImportJob(jobId).status()).isEqualTo("UPLOADED");
      assertThat(jdbc.queryForObject("select count(*) from md_spu", Integer.class)).isZero();

      imports.executePreflight(jobId);

      var preflight = imports.getImportJob(jobId);
      assertThat(preflight.status()).isEqualTo("PREFLIGHT_READY");
      assertThat(preflight.createCount()).isEqualTo(1);
      assertThat(preflight.failureCount()).isEqualTo(1);
      assertThat(preflight.errorObjectKey()).isNotBlank();
      assertThat(objects.exists(preflight.errorObjectKey())).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from md_spu", Integer.class)).isZero();

      imports.confirm(jobId, "confirm-import-1", actor);
      imports.confirm(jobId, "confirm-import-1", actor);
      assertThat(imports.getImportJob(jobId).status()).isEqualTo("CONFIRMED");
      assertThat(jdbc.queryForObject(
          "select count(*) from md_import_job where confirmation_key = 'confirm-import-1'",
          Integer.class)).isEqualTo(1);

      imports.executeConfirmedJob(jobId);

      assertThat(imports.getImportJob(jobId).status()).isEqualTo("PARTIALLY_SUCCEEDED");
      assertThat(jdbc.queryForObject(
          "select count(*) from md_spu where spu_code = 'SPU-IMPORT-1'",
          Integer.class)).isEqualTo(1);
    }
  }

  private static byte[] workbookWithValidAndInvalidRows() throws Exception {
    var template = new ProductWorkbookService().createTemplate();
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
        var output = new ByteArrayOutputStream()) {
      var sheet = workbook.getSheetAt(0);
      var valid = sheet.createRow(1);
      var values = new String[] {
          "SPU-IMPORT-1", "Imported product", "", "", "SKU-IMPORT-1",
          "Imported SKU", "690000000099", "{\"color\":\"black\"}", "piece", "DRAFT"
      };
      for (var column = 0; column < values.length; column++) {
        valid.createCell(column).setCellValue(values[column]);
      }
      var invalid = sheet.createRow(2);
      invalid.createCell(0).setCellValue("SPU-INVALID");
      invalid.createCell(1).setCellValue("Invalid product");
      invalid.createCell(9).setCellValue("DRAFT");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static final class MemoryObjectStore implements ProductObjectStore {
    private final Map<String, byte[]> objects = new HashMap<>();

    @Override
    public StoredProductObject put(
        String objectKey,
        InputStream body,
        long size,
        String contentType,
        String sha256) {
      try {
        var bytes = body.readAllBytes();
        assertThat(bytes).hasSize((int) size);
        var actual = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(actual).isEqualTo(sha256);
        objects.put(objectKey, bytes);
        return new StoredProductObject(objectKey, size, contentType, sha256);
      } catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    }

    @Override
    public InputStream get(String objectKey) {
      return new ByteArrayInputStream(objects.get(objectKey));
    }

    @Override
    public boolean exists(String objectKey) {
      return objects.containsKey(objectKey);
    }

    @Override
    public void deleteUnreferenced(String objectKey) {
      objects.remove(objectKey);
    }
  }
}
