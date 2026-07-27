package com.company.erp.pinduoduo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.erp.pinduoduo.auth.AuthorizationService.AuthorizationRequiredException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class AuthorizationServiceIT {
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4").withDatabaseName("pdd_connector");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-26T08:00:00Z"), ZoneOffset.UTC);
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  private JdbcTemplate jdbc;
  private AuthorizationService service;

  @BeforeAll
  static void startDatabase() {
    MYSQL.start();
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @AfterAll
  static void stopDatabase() {
    MYSQL.stop();
  }

  @BeforeEach
  void setUp() {
    var dataSource =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("delete from pdd_audit_log");
    jdbc.update("delete from pdd_authorization");
    service = new AuthorizationService(jdbc, new AesGcmTokenCipher(KEY), CLOCK);
  }

  @Test
  void authorizationIsUniqueEncryptedAndScopedByShop() {
    service.authorize(
        "SHOP-1", Set.of("ORDER_READ"), "access-1", "refresh-1",
        CLOCK.instant().plusSeconds(3600));
    service.authorize(
        "SHOP-1", Set.of("ORDER_READ", "MESSAGE_READ"), "access-2", "refresh-2",
        CLOCK.instant().plusSeconds(7200));

    var stored = jdbc.queryForMap(
        "select access_token_ciphertext, refresh_token_ciphertext from pdd_authorization "
            + "where shop_id = 'SHOP-1'");
    assertThat(jdbc.queryForObject("select count(*) from pdd_authorization", Integer.class))
        .isEqualTo(1);
    assertThat(stored.get("access_token_ciphertext").toString()).doesNotContain("access-2");
    assertThat(stored.get("refresh_token_ciphertext").toString()).doesNotContain("refresh-2");
    assertThat(service.requireActive("SHOP-1", "ORDER_READ").accessToken()).isEqualTo("access-2");
  }

  @Test
  void anotherShopCannotUseTheAuthorization() {
    service.authorize(
        "SHOP-1", Set.of("ORDER_READ"), "access", "refresh",
        CLOCK.instant().plusSeconds(3600));

    assertThatThrownBy(() -> service.requireActive("SHOP-2", "ORDER_READ"))
        .isInstanceOf(AuthorizationRequiredException.class);
  }

  @Test
  void missingScopeIsRejected() {
    service.authorize(
        "SHOP-1", Set.of("ORDER_READ"), "access", "refresh",
        CLOCK.instant().plusSeconds(3600));

    assertThatThrownBy(() -> service.requireActive("SHOP-1", "MESSAGE_READ"))
        .isInstanceOf(AuthorizationRequiredException.class)
        .hasMessageContaining("scope");
  }

  @Test
  void expiredAuthorizationIsRejected() {
    service.authorize(
        "SHOP-1", Set.of("ORDER_READ"), "access", "refresh",
        CLOCK.instant().minusSeconds(1));

    assertThatThrownBy(() -> service.requireActive("SHOP-1", "ORDER_READ"))
        .isInstanceOf(AuthorizationRequiredException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void authorizationRollsBackWhenAuditWriteFails() {
    jdbc.execute("rename table pdd_audit_log to pdd_audit_log_unavailable");
    try {
      assertThatThrownBy(() -> service.authorize(
          "SHOP-1", Set.of("ORDER_READ"), "access", "refresh",
          CLOCK.instant().plusSeconds(3600)))
          .isInstanceOf(RuntimeException.class);

      assertThat(jdbc.queryForObject("select count(*) from pdd_authorization", Integer.class))
          .isZero();
    } finally {
      jdbc.execute("rename table pdd_audit_log_unavailable to pdd_audit_log");
    }
  }
}
