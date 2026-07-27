package com.company.erp.masterdata.product;

import static com.company.erp.masterdata.product.ProductCommands.AuditActor;
import static com.company.erp.masterdata.product.ProductCommands.CreateSku;
import static com.company.erp.masterdata.product.ProductCommands.CreateSpu;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
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

class ProductImageServiceIT {
  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");
  private static final byte[] PNG = new byte[] {
      (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
  };
  private static final byte[] JPEG = new byte[] {
      (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3
  };

  @Test
  void archivesReordersAndRemovesValidatedImagesWithAudit() {
    try (var mysql = mysql()) {
      mysql.start();
      var fixture = fixture(mysql);
      var actor = new AuditActor("product-admin-1", Set.of("PRODUCT_ADMIN"));
      var productId = fixture.catalog.createSpu(product(), actor);

      var pngId = fixture.catalog.addImage(
          productId,
          new ByteArrayInputStream(PNG),
          PNG.length,
          "application/octet-stream",
          0,
          actor);
      var duplicatePngId = fixture.catalog.addImage(
          productId,
          new ByteArrayInputStream(PNG),
          PNG.length,
          "image/png",
          1,
          actor);
      assertThat(duplicatePngId).isEqualTo(pngId);
      assertThat(fixture.objects.count()).isEqualTo(1);
      assertThat(fixture.queries.get(productId).orElseThrow().version()).isEqualTo(1);
      var jpegId = fixture.catalog.addImage(
          productId,
          new ByteArrayInputStream(JPEG),
          JPEG.length,
          "image/png",
          1,
          actor);

      var detail = fixture.queries.get(productId).orElseThrow();
      assertThat(detail.images()).extracting(ProductViews.ProductImageView::id)
          .containsExactly(pngId, jpegId);
      assertThat(detail.images()).extracting(ProductViews.ProductImageView::mediaType)
          .containsExactly("image/png", "image/jpeg");
      assertThat(detail.images().getFirst().objectKey())
          .startsWith("products/" + productId + "/")
          .endsWith(".png");
      assertThat(fixture.objects.exists(detail.images().getFirst().objectKey())).isTrue();

      fixture.catalog.reorderImages(productId, List.of(jpegId, pngId), 2, actor);
      assertThat(fixture.queries.get(productId).orElseThrow().images())
          .extracting(ProductViews.ProductImageView::id)
          .containsExactly(jpegId, pngId);

      var removedKey = fixture.queries.get(productId).orElseThrow().images().get(1).objectKey();
      fixture.catalog.removeImage(productId, pngId, 3, "Replace old main image", actor);

      var afterRemoval = fixture.queries.get(productId).orElseThrow();
      assertThat(afterRemoval.images()).extracting(ProductViews.ProductImageView::id)
          .containsExactly(jpegId);
      assertThat(fixture.objects.exists(removedKey)).isFalse();
      assertThat(afterRemoval.version()).isEqualTo(4);
      assertThat(fixture.jdbc.queryForObject(
          """
          select count(*) from audit_log
          where aggregate_id = ? and action like 'PRODUCT_IMAGE_%'
          """,
          Integer.class,
          productId)).isEqualTo(4);
    }
  }

  @Test
  void rejectsContentThatDoesNotMatchAnAllowedImageFormat() {
    try (var mysql = mysql()) {
      mysql.start();
      var fixture = fixture(mysql);
      var actor = new AuditActor("product-admin-1", Set.of("PRODUCT_ADMIN"));
      var productId = fixture.catalog.createSpu(product(), actor);
      var fakeImage = "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8);

      assertThatThrownBy(() -> fixture.catalog.addImage(
          productId,
          new ByteArrayInputStream(fakeImage),
          fakeImage.length,
          "image/png",
          0,
          actor))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("JPEG, PNG, or WebP");

      assertThat(fixture.objects.count()).isZero();
      assertThat(fixture.queries.get(productId).orElseThrow().images()).isEmpty();
    }
  }

  private static MySQLContainer<?> mysql() {
    return new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName("erp")
        .withUsername("erp")
        .withPassword("erp");
  }

  private static Fixture fixture(MySQLContainer<?> mysql) {
    var dataSource = new DriverManagerDataSource(
        mysql.getJdbcUrl(),
        mysql.getUsername(),
        mysql.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    var jdbc = new JdbcTemplate(dataSource);
    var transactions = new DataSourceTransactionManager(dataSource);
    var objects = new MemoryObjectStore();
    ProductCatalogService catalog = new JdbcProductCatalogService(jdbc, transactions, objects);
    return new Fixture(jdbc, catalog, new JdbcProductQueryService(jdbc), objects);
  }

  private static CreateSpu product() {
    return new CreateSpu(
        "SPU-IMAGE-1",
        "Image product",
        null,
        null,
        Map.of(),
        List.of(new CreateSku(
            "SKU-IMAGE-1",
            "Image SKU",
            null,
            Map.of(),
            "piece")));
  }

  private record Fixture(
      JdbcTemplate jdbc,
      ProductCatalogService catalog,
      ProductQueryService queries,
      MemoryObjectStore objects) {
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

    int count() {
      return objects.size();
    }
  }
}
