package com.company.erp.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class MinioProductObjectStoreIT {
  private static final DockerImageName MINIO_IMAGE = DockerImageName.parse(
      "minio/minio:RELEASE.2025-04-22T22-12-26Z");

  @Test
  void storesAndReadsAProductObjectWithItsChecksum() throws Exception {
    try (var minio = new GenericContainer<>(MINIO_IMAGE)
        .withEnv("MINIO_ROOT_USER", "erp_local")
        .withEnv("MINIO_ROOT_PASSWORD", "erp_local_secret")
        .withCommand("server", "/data")
        .withExposedPorts(9000)) {
      minio.start();
      var properties = new ProductStorageProperties(
          "http://" + minio.getHost() + ":" + minio.getMappedPort(9000),
          "erp_local",
          "erp_local_secret",
          "erp-products");
      var store = new MinioProductObjectStore(properties);
      var bytes = "product-workbook".getBytes(StandardCharsets.UTF_8);
      var sha256 = HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(bytes));

      var stored = store.put(
          "imports/JOB-1/source.xlsx",
          new ByteArrayInputStream(bytes),
          bytes.length,
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          sha256);

      assertThat(stored.objectKey()).isEqualTo("imports/JOB-1/source.xlsx");
      assertThat(stored.sha256()).isEqualTo(sha256);
      assertThat(store.get(stored.objectKey()).readAllBytes()).isEqualTo(bytes);

      store.deleteUnreferenced(stored.objectKey());
      assertThat(store.exists(stored.objectKey())).isFalse();
    }
  }
}
