package com.company.erp.order;

import com.company.erp.shared.Ids;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class RawOrderArchive {
  private final JdbcTemplate jdbc;

  public RawOrderArchive(JdbcTemplate jdbc) {
    if (jdbc == null) {
      throw new IllegalArgumentException("jdbc is required");
    }
    this.jdbc = jdbc;
  }

  public boolean append(
      String platform,
      String shopId,
      String platformOrderId,
      String payloadRef,
      String payloadSha256) {
    requireText(platform, "platform");
    requireText(shopId, "shopId");
    requireText(platformOrderId, "platformOrderId");
    requireText(payloadRef, "payloadRef");
    if (payloadSha256 == null || !payloadSha256.matches("[0-9a-fA-F]{64}")) {
      throw new IllegalArgumentException("payloadSha256 must be a 64-character hexadecimal digest");
    }

    try {
      jdbc.update(
          "insert into ord_raw_order_version "
              + "(id, platform, shop_id, platform_order_id, payload_ref, payload_sha256) "
              + "values (?, ?, ?, ?, ?, ?)",
          Ids.newId(),
          platform,
          shopId,
          platformOrderId,
          payloadRef,
          payloadSha256.toLowerCase());
      return true;
    } catch (DuplicateKeyException duplicatePayload) {
      return false;
    }
  }

  public List<RawOrderVersion> versions(String platform, String shopId, String platformOrderId) {
    return jdbc.query(
        "select id, platform, shop_id, platform_order_id, payload_ref, payload_sha256, received_at "
            + "from ord_raw_order_version "
            + "where platform = ? and shop_id = ? and platform_order_id = ? "
            + "order by received_at, id",
        (resultSet, rowNumber) -> new RawOrderVersion(
            resultSet.getString("id"),
            resultSet.getString("platform"),
            resultSet.getString("shop_id"),
            resultSet.getString("platform_order_id"),
            resultSet.getString("payload_ref"),
            resultSet.getString("payload_sha256"),
            resultSet.getTimestamp("received_at").toInstant()),
        requireText(platform, "platform"),
        requireText(shopId, "shopId"),
        requireText(platformOrderId, "platformOrderId"));
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  public record RawOrderVersion(
      String id,
      String platform,
      String shopId,
      String platformOrderId,
      String payloadRef,
      String payloadSha256,
      Instant receivedAt) {
  }
}
