package com.company.erp.shared;

import java.time.Clock;
import java.time.Instant;

public record EventEnvelope<T>(
    String eventId,
    String eventType,
    String aggregateId,
    Instant occurredAt,
    T payload) {

  public static <T> EventEnvelope<T> create(
      String eventType,
      String aggregateId,
      T payload,
      Clock clock) {
    return new EventEnvelope<>(
        Ids.newId(),
        eventType,
        aggregateId,
        clock.instant(),
        payload);
  }
}
