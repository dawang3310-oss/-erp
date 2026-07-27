package com.company.erp.masterdata.product;

import static com.company.erp.masterdata.product.ProductCommands.AuditActor;
import static com.company.erp.masterdata.product.ProductCommands.CreateSku;
import static com.company.erp.masterdata.product.ProductCommands.CreateSpu;
import static com.company.erp.masterdata.product.ProductCommands.UpdateSku;
import static com.company.erp.masterdata.product.ProductCommands.UpdateSpu;
import static com.company.erp.masterdata.product.ProductErrors.ProductConflictException;
import static com.company.erp.masterdata.product.ProductErrors.StaleProductVersionException;
import static com.company.erp.masterdata.product.ProductViews.ProductFilter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class ProductCatalogServiceIT {
  static {
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
  }

  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void createsQueriesAndMovesAProductThroughItsAuditedLifecycle() {
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
      ProductCatalogService catalog = new JdbcProductCatalogService(jdbc, transactions);
      ProductQueryService queries = new JdbcProductQueryService(jdbc);
      var actor = new AuditActor("product-admin-1", Set.of("PRODUCT_ADMIN"));

      var productId = catalog.createSpu(product("SPU-1", "SKU-1", "690000000001"), actor);

      var detail = queries.get(productId).orElseThrow();
      assertThat(detail.spuCode()).isEqualTo("SPU-1");
      assertThat(detail.status()).isEqualTo(ProductStatus.DRAFT);
      assertThat(detail.skus()).singleElement()
          .extracting(ProductViews.ProductSkuView::skuCode)
          .isEqualTo("SKU-1");
      assertThat(queries.list(
          new ProductFilter("SKU-1", null, null, null, null),
          0,
          20).total()).isEqualTo(1);

      var existingSku = detail.skus().getFirst();
      catalog.updateSpu(
          productId,
          new UpdateSpu(
              "智能水杯升级版",
              null,
              null,
              Map.of("material", "钛合金"),
              List.of(new UpdateSku(
                  existingSku.id(),
                  "曜石黑 500ml",
                  existingSku.barcode(),
                  Map.of("color", "曜石黑", "capacity", "500ml"),
                  "件",
                  ProductStatus.DRAFT,
                  existingSku.version())),
              List.of(new CreateSku(
                  "SKU-2",
                  "雪山白 500ml",
                  "690000000002",
                  Map.of("color", "雪山白", "capacity", "500ml"),
                  "件")),
              detail.version()),
          actor);
      var edited = queries.get(productId).orElseThrow();
      assertThat(edited.name()).isEqualTo("智能水杯升级版");
      assertThat(edited.attributes()).containsEntry("material", "钛合金");
      assertThat(edited.skus()).extracting(ProductViews.ProductSkuView::skuCode)
          .containsExactly("SKU-1", "SKU-2");
      assertThat(edited.skus()).filteredOn(sku -> sku.skuCode().equals("SKU-1"))
          .singleElement()
          .extracting(ProductViews.ProductSkuView::name)
          .isEqualTo("曜石黑 500ml");

      assertThatThrownBy(() -> catalog.createSpu(
          product("SPU-2", "SKU-1", "690000000002"),
          actor))
          .isInstanceOf(ProductConflictException.class)
          .hasMessageContaining("SKU-1");

      catalog.changeStatus(
          productId,
          ProductStatus.ACTIVE,
          edited.version(),
          "审核通过",
          actor);
      var active = queries.get(productId).orElseThrow();
      assertThat(active.status()).isEqualTo(ProductStatus.ACTIVE);

      assertThatThrownBy(() -> catalog.changeStatus(
          productId,
          ProductStatus.DISABLED,
          detail.version(),
          "使用旧版本",
          actor))
          .isInstanceOf(StaleProductVersionException.class);

      catalog.changeStatus(
          productId,
          ProductStatus.ARCHIVED,
          active.version(),
          "停止经营",
          actor);
      var archived = queries.get(productId).orElseThrow();
      assertThat(archived.status()).isEqualTo(ProductStatus.ARCHIVED);
      assertThatThrownBy(() -> catalog.changeStatus(
          productId,
          ProductStatus.ACTIVE,
          archived.version(),
          "错误恢复",
          actor))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ARCHIVED");

      assertThat(jdbc.queryForObject(
          "select count(*) from audit_log where aggregate_id = ?",
          Integer.class,
          productId)).isEqualTo(4);
      assertThat(jdbc.queryForObject(
          """
          select count(*) from audit_log
          where aggregate_id = ?
            and action in ('PRODUCT_UPDATED', 'PRODUCT_STATUS_CHANGED')
            and before_json is not null
            and after_json is not null
          """,
          Integer.class,
          productId)).isEqualTo(3);
    }
  }

  private static CreateSpu product(String spuCode, String skuCode, String barcode) {
    return new CreateSpu(
        spuCode,
        "智能水杯",
        null,
        null,
        Map.of("material", "不锈钢"),
        List.of(new CreateSku(
            skuCode,
            "黑色 500ml",
            barcode,
            Map.of("color", "黑色", "capacity", "500ml"),
            "件")));
  }
}
