package com.company.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.erp.connectors.NormalizedOrder;
import com.company.erp.fulfillment.FulfillmentService;
import com.company.erp.fulfillment.RoutingPolicy;
import com.company.erp.inventory.InventoryService;
import com.company.erp.masterdata.JdbcMasterDataService;
import com.company.erp.order.OrderIngestionService;
import com.company.erp.outbox.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class OrderToFulfillmentE2EIT {
  static {
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
  }

  @Test
  void paidOrderBecomesReservedFulfillment() {
    var image = DockerImageName.parse("public.ecr.aws/docker/library/mysql:8.4")
        .asCompatibleSubstituteFor("mysql");
    try (var mysql = new MySQLContainer<>(image)
        .withDatabaseName("erp").withUsername("erp").withPassword("erp")) {
      mysql.start();
      var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
      Flyway.configure().dataSource(dataSource).load().migrate();
      var jdbc = new JdbcTemplate(dataSource);
      var tx = new DataSourceTransactionManager(dataSource);
      var clock = Clock.fixed(Instant.parse("2026-07-19T09:00:00Z"), ZoneOffset.UTC);
      var masterData = new JdbcMasterDataService(jdbc);
      var shopId = masterData.createShop("PINDUODUO", "PDD-1", "拼多多店铺");
      var warehouseId = masterData.createWarehouse("WH-1", "一号仓", "SELF_WAREHOUSE");
      masterData.createSku("SKU-1", "690000000011", false, false);
      masterData.mapChannelSku("PINDUODUO", shopId, "PDD-SKU-1", "SKU-1");
      var inventory = new InventoryService(jdbc, tx);
      inventory.receive(warehouseId, "SKU-1", 5, "RECEIPT-1");
      var fulfillment = new FulfillmentService(jdbc);
      RoutingPolicy routing = (ignoredShop, ignoredRegion, ignoredSkus) -> List.of(warehouseId);
      var outbox = new OutboxService(jdbc, new ObjectMapper(), clock, tx);
      var ingestion = new OrderIngestionService(
          jdbc, masterData, tx, inventory, fulfillment, routing, outbox, clock);

      var result = ingestion.ingest(new NormalizedOrder(
          "PINDUODUO", shopId, "PO-1", clock.instant(), "ciphertext:v1:receiver",
          List.of(new NormalizedOrder.Line("PDD-SKU-1", 2, 19900)), "oss://raw/PO-1.json"));

      assertThat(result.status()).isEqualTo("READY_TO_FULFILL");
      assertThat(fulfillment.findByOrder(result.orderId())).singleElement()
          .satisfies(order -> {
            assertThat(order.warehouseId()).isEqualTo(warehouseId);
            assertThat(order.status()).isEqualTo("RESERVED");
          });
      assertThat(inventory.available(warehouseId, "SKU-1")).isEqualTo(3);
      assertThat(outbox.pending(100)).extracting(OutboxService.OutboxRow::eventType)
          .contains("FulfillmentCreated");
    }
  }
}
