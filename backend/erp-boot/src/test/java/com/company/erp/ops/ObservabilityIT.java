package com.company.erp.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.context.annotation.Import;

@AutoConfigureObservability
@SpringBootTest(
    classes = ObservabilityIT.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIT {
  private static final Instant NOW = Instant.parse("2026-07-25T02:00:00Z");

  @LocalServerPort
  private int port;

  @Test
  void exposes_core_order_flow_metrics() {
    var registry = new SimpleMeterRegistry();
    var metrics = new OrderMetrics(registry, Clock.fixed(NOW, ZoneOffset.UTC));

    metrics.orderIngested(NOW.minusSeconds(27));
    metrics.reservationFailed();
    metrics.outboxPending(4);

    assertThat(registry.get("erp.order.ingestion").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("erp.order.ingestion.lag.seconds").gauge().value()).isEqualTo(27.0);
    assertThat(registry.get("erp.inventory.reservation.failure").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("erp.outbox.pending").gauge().value()).isEqualTo(4.0);
  }

  @Test
  void health_and_metrics_endpoints_are_safe() throws Exception {
    var client = HttpClient.newHttpClient();
    var health = get(client, "/actuator/health");
    var metrics = get(client, "/actuator/prometheus");

    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).doesNotContain("password", "token", "receiver");
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body())
        .contains("erp_order_ingestion_total")
        .contains("erp_order_ingestion_lag_seconds")
        .contains("erp_inventory_reservation_failure_total")
        .contains("erp_outbox_pending");
  }

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = {
      DataSourceAutoConfiguration.class,
      FlywayAutoConfiguration.class,
      SecurityAutoConfiguration.class,
      UserDetailsServiceAutoConfiguration.class,
      ManagementWebSecurityAutoConfiguration.class,
      OAuth2ResourceServerAutoConfiguration.class
  })
  @Import(OrderMetrics.class)
  static class TestApplication {
  }
}
