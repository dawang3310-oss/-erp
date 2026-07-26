package com.company.erp.masterdata.importing;

import static com.company.erp.masterdata.product.ProductCommands.AuditActor;
import static com.company.erp.masterdata.product.ProductCommands.CreateSku;
import static com.company.erp.masterdata.product.ProductCommands.CreateSpu;
import static com.company.erp.masterdata.product.ProductViews.ProductFilter;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.erp.masterdata.product.JdbcProductCatalogService;
import com.company.erp.masterdata.product.JdbcProductQueryService;
import com.company.erp.masterdata.product.ProductObjectStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
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

class ProductExportServiceIT {
  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void createsOneIdempotentJobAndExportsTheFilteredWorkbook() throws Exception {
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
      var catalog = new JdbcProductCatalogService(jdbc, transactions);
      var queries = new JdbcProductQueryService(jdbc);
      var actor = new AuditActor("product-admin-1", Set.of("PRODUCT_ADMIN"));
      catalog.createSpu(product("SPU-EXPORT-1", "SKU-EXPORT-1"), actor);
      catalog.createSpu(product("SPU-OTHER", "SKU-OTHER"), actor);
      ProductImportService jobs = new JdbcProductImportService(
          jdbc,
          transactions,
          objects,
          new ProductWorkbookService(),
          catalog,
          queries);
      var filter = new ProductFilter("SPU-EXPORT-1", null, null, null, null);

      var jobId = jobs.createExport(filter, "export-request-1", actor);
      var duplicateJobId = jobs.createExport(filter, "export-request-1", actor);

      assertThat(duplicateJobId).isEqualTo(jobId);
      assertThat(jobs.getExportJob(jobId).status()).isEqualTo("QUEUED");
      assertThat(jdbc.queryForObject(
          "select count(*) from md_export_job where idempotency_key = 'export-request-1'",
          Integer.class)).isEqualTo(1);

      jobs.executeExportJob(jobId);
      jobs.executeExportJob(jobId);

      var completed = jobs.getExportJob(jobId);
      assertThat(completed.status()).isEqualTo("SUCCEEDED");
      assertThat(completed.objectKey()).isNotBlank();
      assertThat(objects.exists(completed.objectKey())).isTrue();
      try (var workbook = new XSSFWorkbook(objects.get(completed.objectKey()))) {
        var sheet = workbook.getSheetAt(0);
        assertThat(sheet.getLastRowNum()).isEqualTo(1);
        assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
            .isEqualTo("SPU-EXPORT-1");
        assertThat(sheet.getRow(1).getCell(4).getStringCellValue())
            .isEqualTo("SKU-EXPORT-1");
      }
    }
  }

  private static CreateSpu product(String spuCode, String skuCode) {
    return new CreateSpu(
        spuCode,
        "Export product",
        null,
        null,
        Map.of(),
        List.of(new CreateSku(
            skuCode,
            "Export SKU",
            null,
            Map.of("color", "black"),
            "piece")));
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
