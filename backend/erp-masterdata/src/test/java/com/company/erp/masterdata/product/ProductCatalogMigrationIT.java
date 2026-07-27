package com.company.erp.masterdata.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class ProductCatalogMigrationIT {
  static {
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
  }

  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void migratesExistingSkuIntoAProductCatalogWithoutLosingItsCode() throws Exception {
    try (var mysql = new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName("erp")
        .withUsername("erp")
        .withPassword("erp")) {
      mysql.start();
      var dataSource = new DriverManagerDataSource(
          mysql.getJdbcUrl(),
          mysql.getUsername(),
          mysql.getPassword());
      Flyway.configure().dataSource(dataSource).target("1").load().migrate();
      var jdbc = new JdbcTemplate(dataSource);
      var legacySkuId = "01J00000000000000000000001";
      jdbc.update(
          """
          insert into md_sku
            (id, sku_code, barcode, batch_enabled, serial_enabled, status)
          values (?, 'LEGACY-1', '690000000001', false, false, 'ACTIVE')
          """,
          legacySkuId);

      Flyway.configure().dataSource(dataSource).load().migrate();

      assertThat(tableExists(dataSource.getConnection(), "md_spu")).isTrue();
      assertThat(jdbc.queryForObject(
          "select count(*) from md_spu where spu_code = ?",
          Integer.class,
          "LEGACY-" + legacySkuId)).isEqualTo(1);
      assertThat(jdbc.queryForObject(
          "select spu_id from md_sku where sku_code = 'LEGACY-1'",
          String.class)).isEqualTo(legacySkuId);
      assertThat(jdbc.queryForObject(
          "select sku_code from md_sku where id = ?",
          String.class,
          legacySkuId)).isEqualTo("LEGACY-1");
    }
  }

  private static boolean tableExists(Connection connection, String tableName) throws Exception {
    try (connection; ResultSet tables = connection.getMetaData().getTables(
        connection.getCatalog(),
        null,
        tableName,
        new String[] {"TABLE"})) {
      return tables.next();
    }
  }
}
