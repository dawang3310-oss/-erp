package com.company.erp.masterdata.importing;

import java.time.Instant;

public final class ProductJobViews {
  private ProductJobViews() {
  }

  public record ImportJobView(
      String id,
      String filename,
      String status,
      int createCount,
      int updateCount,
      int skipCount,
      int conflictCount,
      int failureCount,
      String errorObjectKey,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt) {
  }
}
