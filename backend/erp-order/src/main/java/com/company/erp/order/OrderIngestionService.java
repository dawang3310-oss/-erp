package com.company.erp.order;

import com.company.erp.connectors.IngestionResult;
import com.company.erp.connectors.NormalizedOrder;
import com.company.erp.connectors.OrderIngestionPort;
import com.company.erp.masterdata.MasterDataService;
import com.company.erp.shared.Ids;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class OrderIngestionService implements OrderIngestionPort {
  private final JdbcTemplate jdbc;
  private final MasterDataService masterData;
  private final TransactionTemplate transactions;

  public OrderIngestionService(
      JdbcTemplate jdbc,
      MasterDataService masterData,
      PlatformTransactionManager transactionManager) {
    if (jdbc == null || masterData == null || transactionManager == null) {
      throw new IllegalArgumentException("jdbc, masterData and transactionManager are required");
    }
    this.jdbc = jdbc;
    this.masterData = masterData;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public IngestionResult ingest(NormalizedOrder input) {
    validate(input);
    var existing = findExisting(input);
    if (existing != null) {
      return existing;
    }
    try {
      return transactions.execute(status -> create(input));
    } catch (DuplicateKeyException concurrentDuplicate) {
      var winner = findExisting(input);
      if (winner != null) {
        return winner;
      }
      throw concurrentDuplicate;
    }
  }

  private IngestionResult create(NormalizedOrder input) {
    List<MappedLine> mappedLines = new ArrayList<>();
    List<String> missingSkus = new ArrayList<>();
    for (var line : input.lines()) {
      var internalSku = masterData.resolveInternalSku(
          input.platform(), input.shopId(), line.platformSkuId());
      internalSku.ifPresentOrElse(
          sku -> mappedLines.add(new MappedLine(line, sku)),
          () -> {
            mappedLines.add(new MappedLine(line, null));
            missingSkus.add(line.platformSkuId());
          });
    }

    var orderId = Ids.newId();
    var status = missingSkus.isEmpty() ? "READY_TO_RESERVE" : "EXCEPTION";
    jdbc.update(
        "insert into ord_sales_order "
            + "(id, platform, shop_id, platform_order_id, status, paid_at, receiver_ciphertext) "
            + "values (?, ?, ?, ?, ?, ?, ?)",
        orderId,
        input.platform(),
        input.shopId(),
        input.platformOrderId(),
        status,
        Timestamp.from(input.paidAt()),
        input.receiverCiphertext());
    for (var mapped : mappedLines) {
      jdbc.update(
          "insert into ord_sales_order_line "
              + "(id, order_id, platform_sku_id, internal_sku_code, quantity, paid_amount_fen) "
              + "values (?, ?, ?, ?, ?, ?)",
          Ids.newId(),
          orderId,
          mapped.line().platformSkuId(),
          mapped.internalSkuCode(),
          mapped.line().quantity(),
          mapped.line().paidAmountFen());
    }
    if (!missingSkus.isEmpty()) {
      jdbc.update(
          "insert into ord_exception (id, order_id, error_code, detail, status) values (?, ?, ?, ?, ?)",
          Ids.newId(),
          orderId,
          "SKU_NOT_MAPPED",
          "Unmapped platform SKU: " + String.join(", ", missingSkus),
          "OPEN");
      return new IngestionResult(orderId, status, "SKU_NOT_MAPPED");
    }
    return new IngestionResult(orderId, status, "");
  }

  private IngestionResult findExisting(NormalizedOrder input) {
    var results = jdbc.query(
        "select o.id, o.status, coalesce(e.error_code, '') error_code "
            + "from ord_sales_order o left join ord_exception e on e.order_id = o.id and e.status = 'OPEN' "
            + "where o.platform = ? and o.shop_id = ? and o.platform_order_id = ? limit 1",
        (resultSet, rowNumber) -> new IngestionResult(
            resultSet.getString("id"),
            resultSet.getString("status"),
            resultSet.getString("error_code")),
        input.platform(),
        input.shopId(),
        input.platformOrderId());
    return results.isEmpty() ? null : results.getFirst();
  }

  private static void validate(NormalizedOrder input) {
    if (input == null || input.platform() == null || input.platform().isBlank()
        || input.shopId() == null || input.shopId().isBlank()
        || input.platformOrderId() == null || input.platformOrderId().isBlank()
        || input.paidAt() == null || input.receiverCiphertext() == null
        || input.receiverCiphertext().isBlank() || input.lines().isEmpty()) {
      throw new IllegalArgumentException("A complete normalized order with at least one line is required");
    }
    for (var line : input.lines()) {
      if (line.platformSkuId() == null || line.platformSkuId().isBlank()
          || line.quantity() <= 0 || line.paidAmountFen() < 0) {
        throw new IllegalArgumentException("Order lines require a SKU, positive quantity and non-negative amount");
      }
    }
  }

  private record MappedLine(NormalizedOrder.Line line, String internalSkuCode) {
  }
}
