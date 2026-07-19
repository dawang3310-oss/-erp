package com.company.erp.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.erp.connectors.NormalizedOrder;
import com.company.erp.masterdata.JdbcMasterDataService;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class OrderIngestionServiceIT {
  static {
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
  }

  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void importsIdempotentlyAndQuarantinesUnmappedSkus() {
    try (var mysql = new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName("erp")
        .withUsername("erp")
        .withPassword("erp")) {
      mysql.start();
      var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
      Flyway.configure().dataSource(dataSource).load().migrate();
      var jdbc = new JdbcTemplate(dataSource);
      var masterData = new JdbcMasterDataService(jdbc);
      var shopId = masterData.createShop("PINDUODUO", "PDD-FLAGSHIP", "拼多多旗舰店");
      masterData.createSku("SKU-9", "690000000009", false, false);
      masterData.mapChannelSku("PINDUODUO", shopId, "PDD-SKU-9", "SKU-9");
      var service = new OrderIngestionService(
          jdbc,
          masterData,
          new DataSourceTransactionManager(dataSource));
      var query = new OrderQueryService(jdbc);

      var first = service.ingest(order(shopId, "PO-100", "PDD-SKU-9"));
      var duplicate = service.ingest(order(shopId, "PO-100", "PDD-SKU-9"));
      var unmapped = service.ingest(order(shopId, "PO-101", "UNKNOWN"));

      assertThat(duplicate.orderId()).isEqualTo(first.orderId());
      assertThat(jdbc.queryForObject(
          "select count(*) from ord_sales_order where platform_order_id = 'PO-100'",
          Integer.class)).isEqualTo(1);
      assertThat(unmapped.status()).isEqualTo("EXCEPTION");
      assertThat(unmapped.errorCode()).isEqualTo("SKU_NOT_MAPPED");
      assertThat(query.get(unmapped.orderId()).orElseThrow().exceptionCode())
          .isEqualTo("SKU_NOT_MAPPED");
    }
  }

  private static NormalizedOrder order(String shopId, String orderId, String platformSkuId) {
    return new NormalizedOrder(
        "PINDUODUO",
        shopId,
        orderId,
        Instant.parse("2026-07-19T06:00:00Z"),
        "ciphertext:v1:receiver",
        List.of(new NormalizedOrder.Line(platformSkuId, 2, 19900)),
        "oss://raw/" + orderId + ".json");
  }
}
