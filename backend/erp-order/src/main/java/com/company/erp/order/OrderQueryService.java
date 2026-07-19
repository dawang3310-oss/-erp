package com.company.erp.order;

import java.time.Instant;
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
        "select id, platform, shop_id, platform_order_id, status, paid_at "
            + "from ord_sales_order where id = ?",
        (resultSet, rowNumber) -> new Header(
            resultSet.getString("id"),
            resultSet.getString("platform"),
            resultSet.getString("shop_id"),
            resultSet.getString("platform_order_id"),
            resultSet.getString("status"),
            resultSet.getTimestamp("paid_at").toInstant()),
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
        exceptionCodes.isEmpty() ? "" : exceptionCodes.getFirst()));
  }

  private record Header(
      String id,
      String platform,
      String shopId,
      String platformOrderId,
      String status,
      Instant paidAt) {
  }

  public record SalesOrderView(
      String id,
      String platform,
      String shopId,
      String platformOrderId,
      String status,
      Instant paidAt,
      List<SalesOrderLineView> lines,
      String exceptionCode) {
  }

  public record SalesOrderLineView(
      String platformSkuId,
      String internalSkuCode,
      int quantity,
      long paidAmountFen) {
  }
}
