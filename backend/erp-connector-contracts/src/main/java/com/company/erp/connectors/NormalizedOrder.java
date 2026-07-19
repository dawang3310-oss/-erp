package com.company.erp.connectors;

import java.time.Instant;
import java.util.List;

public record NormalizedOrder(
    String platform,
    String shopId,
    String platformOrderId,
    Instant paidAt,
    String receiverCiphertext,
    List<Line> lines,
    String rawPayloadRef) {
  public NormalizedOrder {
    lines = List.copyOf(lines);
  }

  public record Line(String platformSkuId, int quantity, long paidAmountFen) {
  }
}
