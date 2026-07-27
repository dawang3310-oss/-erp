package com.company.erp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class InventoryConcurrencyIT {
  static {
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
  }

  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void onlyOneOfTwoCompetingOrdersCanReserveTheLastUnit() throws Exception {
    try (var mysql = new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName("erp")
        .withUsername("erp")
        .withPassword("erp")) {
      mysql.start();
      var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
      Flyway.configure().dataSource(dataSource).load().migrate();
      var jdbc = new JdbcTemplate(dataSource);
      var service = new InventoryService(jdbc, new DataSourceTransactionManager(dataSource));
      service.receive("WH-1", "SKU-RED-M", 1, "RECEIPT-1");

      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var first = executor.submit(() -> reserveAfterSignal(service, ready, start, "SO-1"));
        var second = executor.submit(() -> reserveAfterSignal(service, ready, start, "SO-2"));
        ready.await();
        start.countDown();
        var results = List.of(first.get(), second.get());

        assertThat(results).filteredOn(result -> result.status().equals("RESERVED")).hasSize(1);
        assertThat(results).filteredOn(result -> result.status().equals("INSUFFICIENT")).hasSize(1);
      }
      assertThat(jdbc.queryForObject(
          "select sellable_qty from inv_balance where warehouse_id = 'WH-1' and sku_code = 'SKU-RED-M'",
          Integer.class)).isZero();
      assertThat(jdbc.queryForObject(
          "select count(*) from inv_ledger where reason_code = 'ORDER_RESERVE'",
          Integer.class)).isEqualTo(1);
    }
  }

  private static ReservationResult reserveAfterSignal(
      InventoryService service,
      CountDownLatch ready,
      CountDownLatch start,
      String orderId) throws InterruptedException {
    ready.countDown();
    start.await();
    return service.reserve(orderId, "WH-1", "SKU-RED-M", 1);
  }
}
