package com.company.erp.pinduoduo.auth;

import java.time.Instant;
import java.util.Set;

public record AuthorizationRecord(
    String id,
    String shopId,
    Set<String> scopes,
    String accessTokenCiphertext,
    String refreshTokenCiphertext,
    Instant expiresAt,
    String status) {
  public AuthorizationRecord {
    scopes = Set.copyOf(scopes);
  }
}
