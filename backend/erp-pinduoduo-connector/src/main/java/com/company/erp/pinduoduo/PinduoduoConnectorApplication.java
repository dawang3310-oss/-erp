package com.company.erp.pinduoduo;

import com.company.erp.pinduoduo.config.PddConnectorProperties;
import com.company.erp.pinduoduo.config.ProductionGate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(PddConnectorProperties.class)
public class PinduoduoConnectorApplication {
  public static void main(String[] args) {
    SpringApplication.run(PinduoduoConnectorApplication.class, args);
  }

  @Bean
  ProductionGate productionGate(PddConnectorProperties properties) {
    return new ProductionGate(properties.productionEnabled());
  }
}
