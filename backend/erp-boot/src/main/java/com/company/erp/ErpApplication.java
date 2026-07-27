package com.company.erp;

import com.company.erp.order.OrderQueryService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class ErpApplication {
  public static void main(String[] args) {
    SpringApplication.run(ErpApplication.class, args);
  }

  @Bean
  OrderQueryService orderQueryService(JdbcTemplate jdbc) {
    return new OrderQueryService(jdbc);
  }
}
