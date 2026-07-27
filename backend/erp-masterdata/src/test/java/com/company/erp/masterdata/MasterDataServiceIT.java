package com.company.erp.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class MasterDataServiceIT {
  static {
    // Docker Engine 29 rejects docker-java's legacy default API version (1.32).
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
  }

  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void createsMasterDataAndResolvesAChannelSku() {
    try (var mysql = new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName("erp")
        .withUsername("erp")
        .withPassword("erp")) {
      mysql.start();
      var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
      Flyway.configure().dataSource(dataSource).load().migrate();
      var jdbc = new JdbcTemplate(dataSource);
      var service = new JdbcMasterDataService(jdbc);

      var shopId = service.createShop("TMALL", "TMALL-FLAGSHIP", "天猫旗舰店");
      var warehouseId = service.createWarehouse("WH-SH-01", "上海自营仓", "SELF_OPERATED");
      var skuId = service.createSku("SKU-RED-M", "690000000001", true, false);
      service.mapChannelSku("TMALL", shopId, "TMALL-SKU-1001", "SKU-RED-M");

      assertThat(service.resolveInternalSku("TMALL", shopId, "TMALL-SKU-1001"))
          .contains("SKU-RED-M");
      assertThat(shopId).hasSize(26);
      assertThat(warehouseId).hasSize(26);
      assertThat(skuId).hasSize(26);
      assertThat(jdbc.queryForObject("select count(*) from md_sku", Integer.class)).isEqualTo(1);
    }
  }
}
