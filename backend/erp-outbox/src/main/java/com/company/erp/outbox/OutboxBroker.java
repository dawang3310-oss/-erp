package com.company.erp.outbox;

public interface OutboxBroker {
  void send(OutboxMessage message);

  record OutboxMessage(String eventId, String eventType, String aggregateId, String payloadJson) {
  }
}
