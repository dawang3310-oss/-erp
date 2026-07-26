package com.company.erp.pinduoduo.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class AuthorizationService {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() {
  };

  private final JdbcTemplate jdbc;
  private final TokenCipher cipher;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public AuthorizationService(JdbcTemplate jdbc, TokenCipher cipher, Clock clock) {
    if (jdbc == null || cipher == null || clock == null) {
      throw new IllegalArgumentException("jdbc, cipher and clock are required");
    }
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.clock = clock;
    var dataSource = jdbc.getDataSource();
    if (dataSource == null) {
      throw new IllegalArgumentException("JdbcTemplate must have a DataSource");
    }
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  public void authorize(
      String shopId,
      Set<String> scopes,
      String accessToken,
      String refreshToken,
      Instant expiresAt) {
    var normalizedShopId = requireText(shopId, "shopId");
    if (scopes == null || scopes.isEmpty() || scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
      throw new IllegalArgumentException("At least one non-blank scope is required");
    }
    if (expiresAt == null) {
      throw new IllegalArgumentException("expiresAt is required");
    }
    var normalizedScopes = new TreeSet<>(scopes);
    var accessTokenCiphertext = cipher.encrypt(accessToken);
    var refreshTokenCiphertext = cipher.encrypt(refreshToken);
    transactions.executeWithoutResult(ignored -> {
      jdbc.update(
          "insert into pdd_authorization "
              + "(id, shop_id, scopes_json, access_token_ciphertext, refresh_token_ciphertext, "
              + "expires_at, status, updated_at) values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?) "
              + "on duplicate key update scopes_json = values(scopes_json), "
              + "access_token_ciphertext = values(access_token_ciphertext), "
              + "refresh_token_ciphertext = values(refresh_token_ciphertext), "
              + "expires_at = values(expires_at), status = 'ACTIVE', updated_at = values(updated_at)",
          newId(),
          normalizedShopId,
          toJson(normalizedScopes),
          accessTokenCiphertext,
          refreshTokenCiphertext,
          Timestamp.from(expiresAt),
          Timestamp.from(clock.instant()));
      jdbc.update(
          "insert into pdd_audit_log "
              + "(id, shop_id, action, business_key, request_id, result_summary, operator_id, occurred_at) "
              + "values (?, ?, 'AUTHORIZATION_UPDATED', ?, null, ?, 'SYSTEM', ?)",
          newId(),
          normalizedShopId,
          normalizedShopId,
          "status=ACTIVE;scopeCount=" + normalizedScopes.size(),
          Timestamp.from(clock.instant()));
    });
  }

  public ActiveAuthorization requireActive(String shopId, String requiredScope) {
    var normalizedShopId = requireText(shopId, "shopId");
    var normalizedScope = requireText(requiredScope, "scope");
    List<AuthorizationRecord> records = jdbc.query(
        "select id, shop_id, scopes_json, access_token_ciphertext, "
            + "refresh_token_ciphertext, expires_at, status "
            + "from pdd_authorization where shop_id = ?",
        (resultSet, rowNumber) -> new AuthorizationRecord(
            resultSet.getString("id"),
            resultSet.getString("shop_id"),
            fromJson(resultSet.getString("scopes_json")),
            resultSet.getString("access_token_ciphertext"),
            resultSet.getString("refresh_token_ciphertext"),
            resultSet.getTimestamp("expires_at").toInstant(),
            resultSet.getString("status")),
        normalizedShopId);
    if (records.isEmpty() || !"ACTIVE".equals(records.getFirst().status())) {
      throw new AuthorizationRequiredException("Active authorization is required for shop");
    }
    var record = records.getFirst();
    if (!record.expiresAt().isAfter(clock.instant())) {
      throw new AuthorizationRequiredException("Authorization is expired");
    }
    if (!record.scopes().contains(normalizedScope)) {
      throw new AuthorizationRequiredException("Required scope is missing");
    }
    return new ActiveAuthorization(
        record.shopId(),
        record.scopes(),
        cipher.decrypt(record.accessTokenCiphertext()),
        cipher.decrypt(record.refreshTokenCiphertext()),
        record.expiresAt());
  }

  private static String toJson(Set<String> scopes) {
    try {
      return JSON.writeValueAsString(scopes);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Scopes cannot be serialized", exception);
    }
  }

  private static Set<String> fromJson(String json) {
    try {
      return JSON.readValue(json, STRING_SET);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored scopes are invalid", exception);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
  }

  public record ActiveAuthorization(
      String shopId,
      Set<String> scopes,
      String accessToken,
      String refreshToken,
      Instant expiresAt) {
    public ActiveAuthorization {
      scopes = Set.copyOf(scopes);
    }
  }

  public static final class AuthorizationRequiredException extends IllegalStateException {
    public AuthorizationRequiredException(String message) {
      super(message);
    }
  }
}
