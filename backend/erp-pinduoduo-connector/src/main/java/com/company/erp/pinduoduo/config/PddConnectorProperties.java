package com.company.erp.pinduoduo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pdd")
public record PddConnectorProperties(boolean productionEnabled) {
}
