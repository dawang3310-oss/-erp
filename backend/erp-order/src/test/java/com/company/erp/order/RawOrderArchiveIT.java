package com.company.erp.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class RawOrderArchiveIT {
  static {
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
  }

  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void storesEachRawPayloadVersionButKeepsOneBusinessIdentity() {
    try (var mysql = new MySQLContainer<>(MYSQL_IMAGE)
        .withDatabaseName("erp")
        .withUsername("erp")
        .withPassword("erp")) {
      mysql.start();
      var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
      Flyway.configure().dataSource(dataSource).load().migrate();
      var archive = new RawOrderArchive(new JdbcTemplate(dataSource));

      assertThat(archive.append("PINDUODUO", "SHOP-1", "PO-100", "oss://raw/a.json", hash('a')))
          .isTrue();
      assertThat(archive.append("PINDUODUO", "SHOP-1", "PO-100", "oss://raw/b.json", hash('b')))
          .isTrue();
      assertThat(archive.append("PINDUODUO", "SHOP-1", "PO-100", "oss://raw/b.json", hash('b')))
          .isFalse();

      assertThat(archive.versions("PINDUODUO", "SHOP-1", "PO-100"))
          .extracting(RawOrderArchive.RawOrderVersion::payloadRef)
          .containsExactly("oss://raw/a.json", "oss://raw/b.json");
    }
  }

  private static String hash(char value) {
    return String.valueOf(value).repeat(64);
  }
}
