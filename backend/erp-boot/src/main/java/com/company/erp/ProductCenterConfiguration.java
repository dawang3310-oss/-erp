package com.company.erp;

import com.company.erp.masterdata.product.JdbcProductCatalogService;
import com.company.erp.masterdata.product.JdbcProductQueryService;
import com.company.erp.masterdata.product.ProductCatalogService;
import com.company.erp.masterdata.product.ProductQueryService;
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
}
