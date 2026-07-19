package com.company.erp.inventory;

import com.company.erp.shared.Ids;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class InventoryService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public InventoryService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    if (jdbc == null || transactionManager == null) {
      throw new IllegalArgumentException("jdbc and transactionManager are required");
    }
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public void receive(String warehouseId, String skuCode, int quantity, String receiptId) {
    requireStockInput(warehouseId, skuCode, quantity);
    requireText(receiptId, "receiptId");
    transactions.executeWithoutResult(status -> {
      appendLedger(warehouseId, skuCode, quantity, "SELLABLE", "PURCHASE_RECEIPT", receiptId);
      jdbc.update(
          "insert into inv_balance "
              + "(warehouse_id, sku_code, sellable_qty, reserved_qty, version) values (?, ?, ?, 0, 0) "
              + "on duplicate key update sellable_qty = sellable_qty + values(sellable_qty), version = version + 1",
          warehouseId,
          skuCode,
          quantity);
    });
  }

  public ReservationResult reserve(
      String orderId,
      String warehouseId,
      String skuCode,
      int quantity) {
    requireText(orderId, "orderId");
    requireStockInput(warehouseId, skuCode, quantity);
    return transactions.execute(status -> reserveInTransaction(orderId, warehouseId, skuCode, quantity));
  }

  private ReservationResult reserveInTransaction(
      String orderId,
      String warehouseId,
      String skuCode,
      int quantity) {
    int changed = jdbc.update(
        "update inv_balance set sellable_qty = sellable_qty - ?, reserved_qty = reserved_qty + ?, "
            + "version = version + 1 where warehouse_id = ? and sku_code = ? and sellable_qty >= ?",
        quantity,
        quantity,
        warehouseId,
        skuCode,
        quantity);
    if (changed == 0) {
      return ReservationResult.insufficient(available(warehouseId, skuCode));
    }
    var reservationId = Ids.newId();
    jdbc.update(
        "insert into inv_reservation "
            + "(id, order_id, warehouse_id, sku_code, quantity, status) values (?, ?, ?, ?, ?, 'RESERVED')",
        reservationId,
        orderId,
        warehouseId,
        skuCode,
        quantity);
    appendLedger(warehouseId, skuCode, -quantity, "SELLABLE", "ORDER_RESERVE", orderId);
    return ReservationResult.reserved(reservationId, available(warehouseId, skuCode));
  }

  private void appendLedger(
      String warehouseId,
      String skuCode,
      int delta,
      String balanceType,
      String reasonCode,
      String sourceId) {
    jdbc.update(
        "insert into inv_ledger "
            + "(id, warehouse_id, sku_code, quantity_delta, balance_type, reason_code, source_id) "
            + "values (?, ?, ?, ?, ?, ?, ?)",
        Ids.newId(), warehouseId, skuCode, delta, balanceType, reasonCode, sourceId);
  }

  private int available(String warehouseId, String skuCode) {
    List<Integer> quantities = jdbc.query(
        "select sellable_qty from inv_balance where warehouse_id = ? and sku_code = ?",
        (resultSet, rowNumber) -> resultSet.getInt(1),
        warehouseId,
        skuCode);
    return quantities.isEmpty() ? 0 : quantities.getFirst();
  }

  private static void requireStockInput(String warehouseId, String skuCode, int quantity) {
    requireText(warehouseId, "warehouseId");
    requireText(skuCode, "skuCode");
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
