package com.company.erp.connectors;

public interface OrderIngestionPort {
  IngestionResult ingest(NormalizedOrder order);
}
