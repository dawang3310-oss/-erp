package com.company.erp.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.erp.shared.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

class OutboxServiceIT {
  private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
  private static MySQLContainer<?> mysql;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;
  private static DataSourceTransactionManager transactionManager;
  private OutboxService service;

  @BeforeAll
  static void startDatabase() {
    System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    var image = DockerImageName.parse("public.ecr.aws/docker/library/mysql:8.4")
        .asCompatibleSubstituteFor("mysql");
    mysql = new MySQLContainer<>(image).withDatabaseName("erp").withUsername("erp").withPassword("erp");
    mysql.start();
    var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    transactionManager = new DataSourceTransactionManager(dataSource);
    transactions = new TransactionTemplate(transactionManager);
  }

  @AfterAll
  static void stopDatabase() {
    mysql.close();
  }

  @BeforeEach
  void resetOutbox() {
    jdbc.update("delete from sys_outbox");
    service = new OutboxService(jdbc, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
  }

  @Test
  void rolledBackBusinessTransactionHasNoOutboxRow() {
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
      service.append(event("EVENT-ROLLBACK"));
      throw new IllegalStateException("business write failed");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(service.pending(100)).isEmpty();
  }

  @Test
  void publisherMarksRowOnlyAfterBrokerAcknowledgement() {
    transactions.executeWithoutResult(status -> service.append(event("EVENT-ACK")));
    var sent = new ArrayList<OutboxBroker.OutboxMessage>();
    var publisher = new OutboxPublisher(service, sent::add, Clock.fixed(NOW, ZoneOffset.UTC));

    publisher.publishBatch();

    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().eventId()).isEqualTo("EVENT-ACK");
    assertThat(service.pending(100)).isEmpty();
  }

  @Test
  void publisherKeepsRowPendingWhenBrokerRejectsTheMessage() {
    transactions.executeWithoutResult(status -> service.append(event("EVENT-RETRY")));
    OutboxBroker rejectingBroker = message -> {
      throw new IllegalStateException("broker unavailable");
    };
    var publisher = new OutboxPublisher(service, rejectingBroker, Clock.fixed(NOW, ZoneOffset.UTC));

    publisher.publishBatch();

    assertThat(jdbc.queryForObject(
        "select concat(status, ':', attempts) from sys_outbox where id = 'EVENT-RETRY'",
        String.class)).isEqualTo("PENDING:1");
  }

  private static EventEnvelope<TestPayload> event(String id) {
    return new EventEnvelope<>(id, "InventoryReserved", "SO-1", NOW, new TestPayload("WH-1"));
  }

  private record TestPayload(String warehouseId) {
  }
}
