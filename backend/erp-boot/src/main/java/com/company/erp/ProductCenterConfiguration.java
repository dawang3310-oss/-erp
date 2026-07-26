package com.company.erp;

import com.company.erp.masterdata.product.JdbcProductCatalogService;
import com.company.erp.masterdata.product.JdbcProductQueryService;
import com.company.erp.masterdata.importing.JdbcProductImportService;
import com.company.erp.masterdata.importing.ProductImportService;
import com.company.erp.masterdata.importing.ProductWorkbookService;
import com.company.erp.masterdata.product.ProductCatalogService;
import com.company.erp.masterdata.product.ProductObjectStore;
import com.company.erp.masterdata.product.ProductQueryService;
import com.company.erp.storage.MinioProductObjectStore;
import com.company.erp.storage.ProductStorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ProductCenterConfiguration {
  @Bean
  ProductCatalogService productCatalogService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      ProductObjectStore objects) {
    return new JdbcProductCatalogService(jdbc, transactionManager, objects);
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

  @Bean
  ProductWorkbookService productWorkbookService() {
    return new ProductWorkbookService();
  }

  @Bean
  ProductImportService productImportService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      ProductObjectStore objects,
      ProductWorkbookService workbooks,
      ProductCatalogService catalog,
      ProductQueryService queries) {
    return new JdbcProductImportService(
        jdbc,
        transactionManager,
        objects,
        workbooks,
        catalog,
        queries);
  }

  @Bean(name = "productJobExecutor")
  TaskExecutor productJobExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("product-job-");
    executor.initialize();
    return executor;
  }
}
