package com.company.erp;

import com.company.erp.masterdata.product.JdbcProductCatalogService;
import com.company.erp.masterdata.product.JdbcProductQueryService;
import com.company.erp.masterdata.product.ProductCatalogService;
import com.company.erp.masterdata.product.ProductObjectStore;
import com.company.erp.masterdata.product.ProductQueryService;
import com.company.erp.storage.MinioProductObjectStore;
import com.company.erp.storage.ProductStorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ProductCenterConfiguration {
  @Bean
  ProductCatalogService productCatalogService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    return new JdbcProductCatalogService(jdbc, transactionManager);
  }

  @Bean
  ProductQueryService productQueryService(JdbcTemplate jdbc) {
    return new JdbcProductQueryService(jdbc);
  }

  @Bean
  ProductObjectStore productObjectStore(
      @Value("${erp.storage.endpoint}") String endpoint,
      @Value("${erp.storage.access-key}") String accessKey,
      @Value("${erp.storage.secret-key}") String secretKey,
      @Value("${erp.storage.product-bucket}") String productBucket) {
    return new MinioProductObjectStore(
        new ProductStorageProperties(endpoint, accessKey, secretKey, productBucket));
  }
}
