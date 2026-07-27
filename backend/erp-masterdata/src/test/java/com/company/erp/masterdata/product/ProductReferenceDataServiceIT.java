package com.company.erp.masterdata.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class ProductReferenceDataServiceIT {
  static {
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
  }

  private static final DockerImageName MYSQL_IMAGE = DockerImageName
      .parse("public.ecr.aws/docker/library/mysql:8.4")
      .asCompatibleSubstituteFor("mysql");

  @Test
  void createsAndSearchesBrandsAndHierarchicalCategories() {
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
      ProductReferenceDataService references =
          new JdbcProductReferenceDataService(new JdbcTemplate(dataSource));

      var brand = references.createBrand("星链");
      references.createBrand("远航");
      var root = references.createCategory("杯具", null);
      var child = references.createCategory("保温杯", root.id());

      assertThat(references.findBrands("星")).containsExactly(brand);
      assertThat(references.findCategories("保温")).containsExactly(child);
      assertThat(child.parentId()).isEqualTo(root.id());
      assertThat(child.path()).isEqualTo("/" + root.id() + "/" + child.id());
      assertThatThrownBy(() -> references.createBrand("星链"))
          .isInstanceOf(ProductErrors.ProductConflictException.class)
          .hasMessageContaining("DUPLICATE_BRAND");
      assertThatThrownBy(() -> references.createCategory("不存在", "MISSING"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("MISSING");
    }
  }
}
