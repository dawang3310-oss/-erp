package com.company.erp.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class OrderMetrics {
  private final Counter ingestedOrders;
  private final Counter reservationFailures;
  private final AtomicLong ingestionLagSeconds = new AtomicLong();
  private final AtomicLong pendingOutboxEvents = new AtomicLong();
  private final Clock clock;

  @Autowired
  public OrderMetrics(MeterRegistry registry) {
    this(registry, Clock.systemUTC());
  }

  OrderMetrics(MeterRegistry registry, Clock clock) {
    this.clock = clock;
    this.ingestedOrders = Counter.builder("erp.order.ingestion")
        .description("Orders accepted by the unified ingestion flow")
        .register(registry);
    this.reservationFailures = Counter.builder("erp.inventory.reservation.failure")
        .description("Inventory reservation attempts rejected for insufficient stock")
        .register(registry);
    Gauge.builder("erp.order.ingestion.lag.seconds", ingestionLagSeconds, AtomicLong::get)
        .description("Lag between platform payment and ERP ingestion")
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder("erp.outbox.pending", pendingOutboxEvents, AtomicLong::get)
        .description("Outbox events waiting to be published")
        .register(registry);
  }

  public void orderIngested(Instant platformPaidAt) {
    ingestedOrders.increment();
    var lag = Math.max(0, clock.instant().getEpochSecond() - platformPaidAt.getEpochSecond());
    ingestionLagSeconds.set(lag);
  }

  public void reservationFailed() {
    reservationFailures.increment();
  }

  public void outboxPending(long pendingCount) {
    if (pendingCount < 0) {
      throw new IllegalArgumentException("pendingCount must be non-negative");
    }
    pendingOutboxEvents.set(pendingCount);
  }
}
