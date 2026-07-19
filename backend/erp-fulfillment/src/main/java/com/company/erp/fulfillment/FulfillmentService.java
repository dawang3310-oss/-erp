package com.company.erp.fulfillment;

import com.company.erp.shared.Ids;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class FulfillmentService {
  private final JdbcTemplate jdbc;

  public FulfillmentService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public FulfillmentOrder create(
      String salesOrderId,
      String warehouseId,
      String fulfillmentType,
      String status) {
    var order = new FulfillmentOrder(
        Ids.newId(), salesOrderId, warehouseId, fulfillmentType, status);
    jdbc.update(
        "insert into ful_order "
            + "(id, sales_order_id, warehouse_id, fulfillment_type, status) values (?, ?, ?, ?, ?)",
        order.id(), order.salesOrderId(), order.warehouseId(), order.type(), order.status());
    return order;
  }

  public List<FulfillmentOrder> findByOrder(String salesOrderId) {
    return jdbc.query(
        "select id, sales_order_id, warehouse_id, fulfillment_type, status "
            + "from ful_order where sales_order_id = ? order by created_at, id",
        (resultSet, rowNumber) -> new FulfillmentOrder(
            resultSet.getString("id"),
            resultSet.getString("sales_order_id"),
            resultSet.getString("warehouse_id"),
            resultSet.getString("fulfillment_type"),
            resultSet.getString("status")),
        salesOrderId);
  }

  public record FulfillmentOrder(
      String id,
      String salesOrderId,
      String warehouseId,
      String type,
      String status) {
  }
}
