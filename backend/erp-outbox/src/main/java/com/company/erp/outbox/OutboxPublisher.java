package com.company.erp.outbox;

import java.time.Clock;

public final class OutboxPublisher {
  private final OutboxService outbox;
  private final OutboxBroker broker;
  private final Clock clock;

  public OutboxPublisher(OutboxService outbox, OutboxBroker broker, Clock clock) {
    this.outbox = outbox;
    this.broker = broker;
    this.clock = clock;
  }

  public void publishBatch() {
    for (var row : outbox.claimPending(100)) {
      try {
        broker.send(row.toMessage());
        outbox.markPublished(row.id(), clock.instant());
      } catch (RuntimeException sendFailure) {
        long delaySeconds = Math.min(3600L, 1L << Math.min(row.attempts(), 11));
        outbox.reschedule(row.id(), clock.instant().plusSeconds(delaySeconds));
      }
    }
  }
}
