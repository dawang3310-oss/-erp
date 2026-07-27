package com.company.erp.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class OrderQueryService {
  private final JdbcTemplate jdbc;

  public OrderQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<SalesOrderView> get(String orderId) {
    var headers = jdbc.query(
        "select id, platform, shop_id, platform_order_id, status, paid_at, receiver_ciphertext "
            + "from ord_sales_order where id = ?",
        (resultSet, rowNumber) -> new Header(
            resultSet.getString("id"),
            resultSet.getString("platform"),
            resultSet.getString("shop_id"),
            resultSet.getString("platform_order_id"),
            resultSet.getString("status"),
            resultSet.getTimestamp("paid_at").toInstant(),
            resultSet.getString("receiver_ciphertext")),
        orderId);
    if (headers.isEmpty()) {
      return Optional.empty();
    }
    var lines = jdbc.query(
        "select platform_sku_id, internal_sku_code, quantity, paid_amount_fen "
            + "from ord_sales_order_line where order_id = ? order by id",
        (resultSet, rowNumber) -> new SalesOrderLineView(
            resultSet.getString("platform_sku_id"),
            resultSet.getString("internal_sku_code"),
            resultSet.getInt("quantity"),
            resultSet.getLong("paid_amount_fen")),
        orderId);
    var exceptionCodes = jdbc.query(
        "select error_code from ord_exception where order_id = ? and status = 'OPEN' order by created_at limit 1",
        (resultSet, rowNumber) -> resultSet.getString("error_code"),
        orderId);
    var header = headers.getFirst();
    return Optional.of(new SalesOrderView(
        header.id(),
        header.platform(),
        header.shopId(),
        header.platformOrderId(),
        header.status(),
        header.paidAt(),
        List.copyOf(lines),
        exceptionCodes.isEmpty() ? "" : exceptionCodes.getFirst(),
        header.receiverCiphertext()));
  }

  public List<SalesOrderView> list(
      List<String> shopIds,
      String status,
      String platform,
      int page,
      int size) {
    if (shopIds == null || shopIds.isEmpty()) {
      return List.of();
    }
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
    }
    var placeholders = String.join(",", Collections.nCopies(shopIds.size(), "?"));
    var sql = new StringBuilder(
        "select id from ord_sales_order where shop_id in (" + placeholders + ")");
    List<Object> parameters = new ArrayList<>(shopIds);
    if (status != null && !status.isBlank()) {
      sql.append(" and status = ?");
      parameters.add(status);
    }
    if (platform != null && !platform.isBlank()) {
      sql.append(" and platform = ?");
      parameters.add(platform);
    }
    sql.append(" order by paid_at desc, id limit ? offset ?");
    parameters.add(size);
    parameters.add(page * size);
    var ids = jdbc.query(
        sql.toString(),
        (resultSet, rowNumber) -> resultSet.getString("id"),
        parameters.toArray());
    return ids.stream().map(this::get).flatMap(Optional::stream).toList();
  }

  private record Header(
      String id,
      String platform,
      String shopId,
      String platformOrderId,
      String status,
      Instant paidAt,
      String receiverCiphertext) {
  }

  public record SalesOrderView(
      String id,
      String platform,
      String shopId,
      String platformOrderId,
      String status,
      Instant paidAt,
      List<SalesOrderLineView> lines,
      String exceptionCode,
      String receiverCiphertext) {
  }

  public record SalesOrderLineView(
      String platformSkuId,
      String internalSkuCode,
      int quantity,
      long paidAmountFen) {
  }
}
