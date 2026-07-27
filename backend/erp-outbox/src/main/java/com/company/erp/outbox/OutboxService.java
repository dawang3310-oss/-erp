package com.company.erp.outbox;

import com.company.erp.shared.EventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class OutboxService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public OutboxService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public void append(EventEnvelope<?> event) {
    jdbc.update(
        "insert into sys_outbox "
            + "(id, event_type, aggregate_id, payload_json, status, attempts, next_attempt_at, created_at) "
            + "values (?, ?, ?, ?, 'PENDING', 0, ?, ?)",
        event.eventId(),
        event.eventType(),
        event.aggregateId(),
        serialize(event.payload()),
        Timestamp.from(clock.instant()),
        Timestamp.from(event.occurredAt()));
  }

  public List<OutboxRow> pending(int limit) {
    return jdbc.query(
        "select id, event_type, aggregate_id, payload_json, attempts from sys_outbox "
            + "where status = 'PENDING' and next_attempt_at <= ? order by created_at limit ?",
        (resultSet, rowNumber) -> new OutboxRow(
            resultSet.getString("id"),
            resultSet.getString("event_type"),
            resultSet.getString("aggregate_id"),
            resultSet.getString("payload_json"),
            resultSet.getInt("attempts")),
        Timestamp.from(clock.instant()),
        limit);
  }

  public List<OutboxRow> claimPending(int limit) {
    return transactions.execute(status -> {
      var rows = jdbc.query(
          "select id, event_type, aggregate_id, payload_json, attempts from sys_outbox "
              + "where status = 'PENDING' and next_attempt_at <= ? order by created_at limit ? for update skip locked",
          (resultSet, rowNumber) -> new OutboxRow(
              resultSet.getString("id"), resultSet.getString("event_type"),
              resultSet.getString("aggregate_id"), resultSet.getString("payload_json"),
              resultSet.getInt("attempts")),
          Timestamp.from(clock.instant()), limit);
      rows.forEach(row -> jdbc.update("update sys_outbox set status = 'PROCESSING' where id = ?", row.id()));
      return rows;
    });
  }

  public void markPublished(String id, Instant publishedAt) {
    jdbc.update(
        "update sys_outbox set status = 'PUBLISHED', published_at = ? where id = ? and status = 'PROCESSING'",
        Timestamp.from(publishedAt), id);
  }

  public void reschedule(String id, Instant nextAttemptAt) {
    jdbc.update(
        "update sys_outbox set status = 'PENDING', attempts = attempts + 1, next_attempt_at = ? "
            + "where id = ? and status = 'PROCESSING'",
        Timestamp.from(nextAttemptAt), id);
  }

  private String serialize(Object payload) {
    try {
      return json.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Event payload is not JSON serializable", exception);
    }
  }

  public record OutboxRow(
      String id, String eventType, String aggregateId, String payloadJson, int attempts) {
    public OutboxBroker.OutboxMessage toMessage() {
      return new OutboxBroker.OutboxMessage(id, eventType, aggregateId, payloadJson);
    }
  }
}
