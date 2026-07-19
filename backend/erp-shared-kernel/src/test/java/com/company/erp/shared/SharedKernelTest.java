package com.company.erp.shared;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedKernelTest {
  @Test
  void creates_non_blank_crockford_base32_id() {
    assertThat(Ids.newId()).matches("[0-9A-HJKMNP-TV-Z]{26}");
  }

  @Test
  void event_envelope_keeps_business_identity_and_clock_time() {
    var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    var event = EventEnvelope.create(
        "OrderImported",
        "SO-1",
        Map.of("shopId", "SHOP-1"),
        clock);

    assertThat(event.eventId()).matches("[0-9A-HJKMNP-TV-Z]{26}");
    assertThat(event.eventType()).isEqualTo("OrderImported");
    assertThat(event.aggregateId()).isEqualTo("SO-1");
    assertThat(event.occurredAt()).isEqualTo(Instant.EPOCH);
    assertThat(event.payload()).containsEntry("shopId", "SHOP-1");
  }

  @Test
  void domain_error_rejects_blank_code_or_message() {
    assertThatThrownBy(() -> new DomainError("", "message"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DomainError("INVALID", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
