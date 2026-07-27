# Pinduoduo Platform Foundation and Order Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a separately deployable Pinduoduo connector that can be fully tested without platform credentials and can safely archive, normalize, deduplicate, and submit paid orders to the ERP through an authenticated internal API.

**Architecture:** Add `erp-pinduoduo-connector` as a standalone Spring Boot process in the existing Maven reactor. Keep Pinduoduo DTOs and operational state inside that module, depend only on stable connector contracts, and communicate with ERP through an HTTP adapter; a simulator implements the same `PddPlatformGateway` port that the credential-backed adapter will implement after official console access is granted.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Maven 3.9+, Spring JDBC, Flyway, MySQL 8.4, Jackson, Micrometer, Testcontainers, JUnit 5, AssertJ, WireMock, JWT resource-server security.

## Global Constraints

- The connector is a separate process; ERP core modules never depend on its implementation.
- Every authorization, cursor, batch, message, archive, audit, and metric record carries the internal `shopId`.
- Production mode defaults to disabled and cannot pull production data or perform platform side effects while disabled.
- Platform tokens and receiver data are encrypted; secrets, tokens, full phone numbers, addresses, and raw payload bodies never enter logs, metrics, or error responses.
- The simulator and the credential-backed adapter implement the same domain port.
- Official API method names, permission codes, rate limits, signatures, and error-code mappings are not invented before authenticated Pinduoduo console documentation is available.
- A polling cursor advances only after every page in the batch is archived, normalized, and accepted idempotently by ERP.
- Messages reduce discovery latency but never replace overlapping scheduled polling.
- Unknown platform states become explicit `UNKNOWN_PLATFORM_STATE` failures and never map silently to a normal ERP state.
- Use test-first changes, focused tests before reactor tests, and one reviewable commit per task.

---

### Task 1: Bootstrap the Standalone Connector and Enforce the Production Gate

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/erp-pinduoduo-connector/pom.xml`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/PinduoduoConnectorApplication.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/config/PddConnectorProperties.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/config/ProductionGate.java`
- Create: `backend/erp-pinduoduo-connector/src/main/resources/application.yml`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/ConnectorBoundaryTest.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/config/ProductionGateTest.java`

**Interfaces:**
- Consumes: environment property `PDD_PRODUCTION_ENABLED`, defaulting to `false`.
- Produces: executable `PinduoduoConnectorApplication`; `ProductionGate.requireReadAllowed(Operation)` and `ProductionGate.requireSideEffectAllowed(Operation)`.

- [ ] **Step 1: Write failing gate and boundary tests**

```java
@Test
void production_business_reads_are_blocked_by_default() {
  var gate = new ProductionGate(false);
  assertThatThrownBy(() -> gate.requireReadAllowed(Operation.ORDER_PULL))
      .isInstanceOf(ProductionDisabledException.class);
}

@Test
void connector_does_not_depend_on_erp_implementation_modules() {
  var classes = new ClassFileImporter().importPackages("com.company.erp.pinduoduo");
  noClasses().should().dependOnClassesThat().resideInAnyPackage(
      "..order..", "..inventory..", "..fulfillment..", "..masterdata..")
      .check(classes);
}
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am -Dtest=ProductionGateTest,ConnectorBoundaryTest test`

Expected: Maven fails because the module and gate types do not exist.

- [ ] **Step 3: Add the reactor module, Boot application, and fail-closed gate**

```java
public final class ProductionGate {
  private final boolean productionEnabled;

  public ProductionGate(boolean productionEnabled) {
    this.productionEnabled = productionEnabled;
  }

  public void requireReadAllowed(Operation operation) {
    if (!productionEnabled && operation.productionBusinessData()) {
      throw new ProductionDisabledException(operation.name());
    }
  }

  public void requireSideEffectAllowed(Operation operation) {
    if (!productionEnabled) {
      throw new ProductionDisabledException(operation.name());
    }
  }
}
```

```yaml
spring:
  application:
    name: pinduoduo-connector
  datasource:
    url: ${PDD_DB_URL:jdbc:mysql://localhost:3306/pdd_connector}
    username: ${PDD_DB_USERNAME:erp}
    password: ${PDD_DB_PASSWORD:erp_local}
pdd:
  production-enabled: ${PDD_PRODUCTION_ENABLED:false}
management.endpoints.web.exposure.include: health,prometheus
```

- [ ] **Step 4: Run module and reactor tests**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am test && mvn -B test`

Expected: both builds end with `BUILD SUCCESS`.

- [ ] **Step 5: Commit the bootstrap**

```bash
git add backend/pom.xml backend/erp-pinduoduo-connector
git commit -m "build: bootstrap pinduoduo connector"
```

### Task 2: Persist Shop Authorization Without Plaintext Secrets

**Files:**
- Create: `backend/erp-pinduoduo-connector/src/main/resources/db/migration/V101__pdd_authorization.sql`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/auth/AuthorizationService.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/auth/TokenCipher.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/auth/AesGcmTokenCipher.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/auth/AuthorizationRecord.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/auth/AuthorizationServiceIT.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/auth/AesGcmTokenCipherTest.java`

**Interfaces:**
- Consumes: `AuthorizationService.authorize(String shopId, Set<String> scopes, String accessToken, String refreshToken, Instant expiresAt)`.
- Produces: `AuthorizationService.requireActive(String shopId, String scope)` returning decrypted `ActiveAuthorization`; unique authorization identity `PINDUODUO + shopId`.

- [ ] **Step 1: Write encryption and shop-isolation tests**

```java
@Test
void ciphertext_does_not_contain_token_and_round_trips() {
  var cipher = new AesGcmTokenCipher(keyBytes);
  var encrypted = cipher.encrypt("access-secret");
  assertThat(encrypted).doesNotContain("access-secret");
  assertThat(cipher.decrypt(encrypted)).isEqualTo("access-secret");
}

@Test
void authorization_is_unique_and_scoped_by_shop() {
  service.authorize("SHOP-1", Set.of("ORDER_READ"), "a1", "r1", clock.instant().plusSeconds(3600));
  assertThat(service.requireActive("SHOP-1", "ORDER_READ").accessToken()).isEqualTo("a1");
  assertThatThrownBy(() -> service.requireActive("SHOP-2", "ORDER_READ"))
      .isInstanceOf(AuthorizationRequiredException.class);
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -Dtest=AesGcmTokenCipherTest,AuthorizationServiceIT test`

Expected: compilation fails because authorization types are missing.

- [ ] **Step 3: Add authorization and audit schema**

```sql
CREATE TABLE pdd_authorization (
  id VARCHAR(26) PRIMARY KEY,
  shop_id VARCHAR(26) NOT NULL,
  scopes_json JSON NOT NULL,
  access_token_ciphertext TEXT NOT NULL,
  refresh_token_ciphertext TEXT NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  UNIQUE(shop_id)
);
CREATE TABLE pdd_audit_log (
  id VARCHAR(26) PRIMARY KEY,
  shop_id VARCHAR(26) NOT NULL,
  action VARCHAR(64) NOT NULL,
  business_key VARCHAR(160) NOT NULL,
  request_id VARCHAR(64),
  result_summary VARCHAR(512) NOT NULL,
  operator_id VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL
);
```

- [ ] **Step 4: Implement AES-256-GCM and authorization lookup**

```java
public interface TokenCipher {
  String encrypt(String plaintext);
  String decrypt(String ciphertext);
}

public record ActiveAuthorization(
    String shopId, Set<String> scopes, String accessToken, String refreshToken, Instant expiresAt) {
}
```

`AesGcmTokenCipher` must generate a fresh 12-byte nonce for every encryption, prepend it to the ciphertext, authenticate with a 256-bit environment-provided key, and encode the result with Base64.

- [ ] **Step 5: Run focused and reactor tests**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am verify && mvn -B test`

Expected: encryption tests and MySQL authorization tests pass without storing plaintext tokens.

- [ ] **Step 6: Commit authorization support**

```bash
git add backend/erp-pinduoduo-connector
git commit -m "feat: secure pinduoduo shop authorization"
```

### Task 3: Define the Stable Platform Gateway and Deterministic Simulator

**Files:**
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/gateway/PddPlatformGateway.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/gateway/PddGatewayException.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/gateway/PddFailureCategory.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/gateway/PddOrderPage.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/simulator/PddPlatformSimulator.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/simulator/PddPlatformSimulatorTest.java`

**Interfaces:**
- Consumes: `PddPlatformGateway.fetchOrders(OrderQuery query)`.
- Produces: `PddOrderPage(List<JsonNode> orders, Optional<String> nextCursor, String requestId, String rawPayload)` and failure categories `RETRYABLE`, `AUTH_REFRESH_REQUIRED`, `PERMANENT`, `STATE_CONFLICT`, `RESULT_UNKNOWN`.

- [ ] **Step 1: Write simulator paging and failure tests**

```java
@Test
void simulator_returns_duplicate_pages_when_scenario_requests_it() {
  simulator.enqueuePage(page("PO-1", "cursor-2"));
  simulator.enqueuePage(page("PO-1", null));
  assertThat(simulator.fetchOrders(query).orders()).hasSize(1);
  assertThat(simulator.fetchOrders(query.withCursor("cursor-2")).orders()).hasSize(1);
}

@Test
void simulator_exposes_typed_rate_limit_failure() {
  simulator.failNext(PddFailureCategory.RETRYABLE, "RATE_LIMITED");
  assertThatThrownBy(() -> simulator.fetchOrders(query))
      .isInstanceOfSatisfying(PddGatewayException.class,
          error -> assertThat(error.category()).isEqualTo(PddFailureCategory.RETRYABLE));
}
```

- [ ] **Step 2: Run tests and verify failure**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -Dtest=PddPlatformSimulatorTest test`

Expected: compilation fails because the gateway and simulator do not exist.

- [ ] **Step 3: Implement the domain gateway and scripted simulator**

```java
public interface PddPlatformGateway {
  PddOrderPage fetchOrders(OrderQuery query);

  record OrderQuery(
      String shopId, Instant updatedFrom, Instant updatedTo, String cursor, int pageSize) {
    public OrderQuery withCursor(String value) {
      return new OrderQuery(shopId, updatedFrom, updatedTo, value, pageSize);
    }
  }
}
```

The simulator stores queued pages and failures in FIFO order, validates `shopId`, and never performs HTTP. No credential-backed implementation is added in this task because authenticated API documentation is not yet available.

- [ ] **Step 4: Run simulator and reactor tests**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am test && mvn -B test`

Expected: simulator tests pass, including duplicate pages, token expiry, retryable failure, unknown result, and state conflict.

- [ ] **Step 5: Commit the stable gateway**

```bash
git add backend/erp-pinduoduo-connector
git commit -m "feat: add pinduoduo platform simulator"
```

### Task 4: Archive Raw Payloads and Persist Transactional Pull Cursors

**Files:**
- Create: `backend/erp-pinduoduo-connector/src/main/resources/db/migration/V102__pdd_order_intake.sql`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/archive/PayloadStore.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/archive/RawPayloadArchive.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/polling/PullBatchRepository.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/polling/PullCursor.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/archive/RawPayloadArchiveIT.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/polling/PullBatchRepositoryIT.java`

**Interfaces:**
- Consumes: raw page body plus `shopId`, business key, request ID, and batch ID.
- Produces: `ArchivedPayload(payloadRef, sha256, duplicate)` and compare-and-set cursor transition `completeBatch(batchId, expectedCursor, nextCursor)`.

- [ ] **Step 1: Write append-only archive and cursor rollback tests**

```java
@Test
void duplicate_content_reuses_archive_identity() {
  var first = archive.append("SHOP-1", "ORDER_PAGE", "PO-1", "REQ-1", "B-1", body);
  var second = archive.append("SHOP-1", "ORDER_PAGE", "PO-1", "REQ-2", "B-2", body);
  assertThat(second.duplicate()).isTrue();
  assertThat(second.sha256()).isEqualTo(first.sha256());
}

@Test
void failed_batch_does_not_advance_cursor() {
  var batch = batches.start("SHOP-1", "ORDER", from, to, "");
  batches.fail(batch.id(), "ERP_REJECTED");
  assertThat(batches.cursor("SHOP-1", "ORDER").platformCursor()).isEmpty();
}
```

- [ ] **Step 2: Run tests and verify failure**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -Dtest=RawPayloadArchiveIT,PullBatchRepositoryIT test`

Expected: missing schema and repository types cause failure.

- [ ] **Step 3: Add archive, cursor, and batch schema**

```sql
CREATE TABLE pdd_raw_payload (
  id VARCHAR(26) PRIMARY KEY, shop_id VARCHAR(26) NOT NULL,
  business_type VARCHAR(32) NOT NULL, business_key VARCHAR(160) NOT NULL,
  request_id VARCHAR(64) NOT NULL, batch_id VARCHAR(26) NOT NULL,
  payload_ref VARCHAR(512) NOT NULL, payload_sha256 CHAR(64) NOT NULL,
  payload_version VARCHAR(32) NOT NULL, fetched_at TIMESTAMP(6) NOT NULL,
  UNIQUE(shop_id, business_type, business_key, payload_sha256)
);
CREATE TABLE pdd_pull_cursor (
  shop_id VARCHAR(26) NOT NULL, capability VARCHAR(32) NOT NULL,
  window_from TIMESTAMP(6), window_to TIMESTAMP(6), platform_cursor VARCHAR(256),
  last_success_at TIMESTAMP(6), version BIGINT NOT NULL,
  PRIMARY KEY(shop_id, capability)
);
CREATE TABLE pdd_pull_batch (
  id VARCHAR(26) PRIMARY KEY, shop_id VARCHAR(26) NOT NULL, capability VARCHAR(32) NOT NULL,
  window_from TIMESTAMP(6) NOT NULL, window_to TIMESTAMP(6) NOT NULL,
  start_cursor VARCHAR(256), end_cursor VARCHAR(256), status VARCHAR(24) NOT NULL,
  failure_code VARCHAR(64), created_at TIMESTAMP(6) NOT NULL, completed_at TIMESTAMP(6)
);
```

- [ ] **Step 4: Implement object-store-first archive and compare-and-set cursor completion**

`PayloadStore.put(String key, byte[] body)` returns an opaque object reference. `RawPayloadArchive` calculates SHA-256, writes the body only through `PayloadStore`, and persists metadata without the raw body. `PullBatchRepository.completeBatch` updates the cursor and batch status in one `TransactionTemplate`.

- [ ] **Step 5: Run integration and reactor tests**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am verify && mvn -B test`

Expected: MySQL tests prove duplicate content is harmless and failed or concurrent batches cannot advance the cursor incorrectly.

- [ ] **Step 6: Commit durable polling state**

```bash
git add backend/erp-pinduoduo-connector
git commit -m "feat: persist pinduoduo pull state"
```

### Task 5: Normalize Golden Order Payloads and Reject Unknown States

**Files:**
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/orders/PddOrderMapper.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/orders/ReceiverCipher.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/orders/OrderMappingException.java`
- Create: `backend/erp-pinduoduo-connector/src/test/resources/pdd/orders/paid-v1.json`
- Create: `backend/erp-pinduoduo-connector/src/test/resources/pdd/orders/unknown-status-v1.json`
- Create: `backend/erp-pinduoduo-connector/src/test/resources/pdd/orders/missing-sku-v1.json`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/orders/PddOrderMapperContractTest.java`

**Interfaces:**
- Consumes: archived `JsonNode` order and raw payload reference.
- Produces: `NormalizedOrder("PINDUODUO", shopId, orderSn, paidAt, receiverCiphertext, lines, payloadRef)`.

- [ ] **Step 1: Write golden payload contract tests**

```java
@Test
void paid_order_maps_money_time_skus_and_encrypted_receiver() {
  var order = mapper.map("SHOP-1", json("paid-v1.json"), "object://payload/sha");
  assertThat(order.platform()).isEqualTo("PINDUODUO");
  assertThat(order.lines()).extracting(NormalizedOrder.Line::paidAmountFen)
      .containsExactly(1299L);
  assertThat(order.receiverCiphertext()).doesNotContain("13800000000");
}

@Test
void unknown_state_is_not_treated_as_paid() {
  assertThatThrownBy(() -> mapper.map("SHOP-1", json("unknown-status-v1.json"), "object://x"))
      .isInstanceOfSatisfying(OrderMappingException.class,
          error -> assertThat(error.code()).isEqualTo("UNKNOWN_PLATFORM_STATE"));
}
```

- [ ] **Step 2: Run the contract test and verify failure**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -Dtest=PddOrderMapperContractTest test`

Expected: compilation fails because mapper types are absent.

- [ ] **Step 3: Implement strict mapping and receiver encryption**

```java
public NormalizedOrder map(String shopId, JsonNode source, String payloadRef) {
  requireState(source.path("order_status").asText());
  var line = new NormalizedOrder.Line(
      requiredText(source, "sku_id"),
      requiredPositiveInt(source, "goods_count"),
      requiredNonNegativeLong(source, "paid_amount_fen"));
  return new NormalizedOrder(
      "PINDUODUO", shopId, requiredText(source, "order_sn"),
      Instant.ofEpochSecond(requiredPositiveLong(source, "pay_time")),
      receiverCipher.encrypt(receiverJson(source)), List.of(line), payloadRef);
}
```

Accepted simulator state is exactly `PAID` for this intake plan. Missing required data receives `INVALID_PLATFORM_PAYLOAD`; every other state receives `UNKNOWN_PLATFORM_STATE` until a versioned mapping is explicitly added.

- [ ] **Step 4: Run mapper and module tests**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am test`

Expected: normal, missing-field, duplicate-version, and unknown-state fixtures pass their explicit assertions.

- [ ] **Step 5: Commit order normalization**

```bash
git add backend/erp-pinduoduo-connector
git commit -m "feat: normalize pinduoduo orders"
```

### Task 6: Submit Orders Through an Authenticated ERP Internal API

**Files:**
- Modify: `backend/erp-boot/src/main/java/com/company/erp/ErpApplication.java`
- Create: `backend/erp-boot/src/main/java/com/company/erp/api/ConnectorOrderController.java`
- Test: `backend/erp-boot/src/test/java/com/company/erp/api/ConnectorOrderControllerIT.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/erp/ErpOrderHttpClient.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/erp/ServiceTokenProvider.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/erp/ErpOrderHttpClientTest.java`

**Interfaces:**
- Consumes: `POST /internal/connectors/orders` with a bearer JWT containing role `CONNECTOR_ORDER_INGEST` and `shopIds`.
- Produces: the existing `IngestionResult`; client implements the existing `OrderIngestionPort`.

- [ ] **Step 1: Write authorization, shop-scope, and HTTP serialization tests**

```java
@Test
void connector_role_can_ingest_an_assigned_shop() {
  postWithJwt("/internal/connectors/orders", normalizedOrder,
      List.of("CONNECTOR_ORDER_INGEST"), List.of("SHOP-1"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("READY_TO_FULFILL"));
}

@Test
void connector_cannot_ingest_an_unassigned_shop() {
  postWithJwt("/internal/connectors/orders", normalizedOrder,
      List.of("CONNECTOR_ORDER_INGEST"), List.of("SHOP-2"))
      .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd backend && mvn -pl erp-boot,erp-pinduoduo-connector -am -Dtest=ConnectorOrderControllerIT,ErpOrderHttpClientTest test`

Expected: ERP returns 404 and the HTTP client type is missing.

- [ ] **Step 3: Add the scoped internal controller**

```java
@PostMapping("/internal/connectors/orders")
@PreAuthorize("hasRole('CONNECTOR_ORDER_INGEST')")
IngestionResult ingest(@RequestBody NormalizedOrder order, JwtAuthenticationToken auth) {
  if (!shopIds(auth).contains(order.shopId())) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Shop is outside connector scope");
  }
  return ingestion.ingest(order);
}
```

Wire `JdbcMasterDataService`, `InventoryService`, `FulfillmentService`, `RoutingPolicy`, `OutboxService`, and `OrderIngestionService` as ERP Boot beans using the existing constructors. The routing bean reads `ERP_DEFAULT_WAREHOUSE_ID` and returns that single warehouse.

- [ ] **Step 4: Implement the HTTP adapter**

```java
public final class ErpOrderHttpClient implements OrderIngestionPort {
  public IngestionResult ingest(NormalizedOrder order) {
    return restClient.post().uri("/internal/connectors/orders")
        .header("Authorization", "Bearer " + tokens.currentToken())
        .body(order).retrieve().body(IngestionResult.class);
  }
}
```

- [ ] **Step 5: Run security, adapter, and reactor tests**

Run: `cd backend && mvn -pl erp-boot,erp-pinduoduo-connector -am verify && mvn -B test`

Expected: missing roles are rejected, cross-shop requests return 403, assigned-shop requests are idempotent, and receiver plaintext is absent from errors.

- [ ] **Step 6: Commit the process boundary**

```bash
git add backend/erp-boot backend/erp-pinduoduo-connector
git commit -m "feat: add authenticated connector order intake"
```

### Task 7: Orchestrate Overlapping Pulls and Transactional Cursor Advancement

**Files:**
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/orders/OrderPullService.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/orders/OrderPullScheduler.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/orders/OrderIntakeFailureRepository.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/orders/OrderPullServiceIT.java`

**Interfaces:**
- Consumes: `OrderPullService.pull(String shopId, Instant now)` and a configurable five-minute overlap window.
- Produces: `PullResult(batchId, pages, accepted, duplicates, status)`; cursor advances only for `COMPLETED`.

- [ ] **Step 1: Write successful, duplicate, and partial-failure tests**

```java
@Test
void cursor_advances_only_after_all_pages_reach_erp() {
  simulator.enqueuePage(page("PO-1", "c2"));
  simulator.enqueuePage(page("PO-2", null));
  var result = pulls.pull("SHOP-1", now);
  assertThat(result.status()).isEqualTo("COMPLETED");
  assertThat(batches.cursor("SHOP-1", "ORDER").windowTo()).isEqualTo(now);
}

@Test
void second_page_failure_keeps_previous_cursor() {
  simulator.enqueuePage(page("PO-1", "c2"));
  simulator.failNext(PddFailureCategory.RETRYABLE, "RATE_LIMITED");
  assertThatThrownBy(() -> pulls.pull("SHOP-1", now)).isInstanceOf(PddGatewayException.class);
  assertThat(batches.cursor("SHOP-1", "ORDER").windowTo()).isEqualTo(previousWindowTo);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -Dtest=OrderPullServiceIT test`

Expected: compilation fails because the pull service is absent.

- [ ] **Step 3: Implement archive-before-map-before-submit sequencing**

```java
while (true) {
  var page = gateway.fetchOrders(query);
  var archived = archive.appendPage(shopId, page.requestId(), batch.id(), page.rawPayload());
  for (var source : page.orders()) {
    var normalized = mapper.map(shopId, source, archived.payloadRef());
    ingestion.ingest(normalized);
  }
  if (page.nextCursor().isEmpty()) break;
  query = query.withCursor(page.nextCursor().orElseThrow());
}
batches.completeBatch(batch.id(), startCursor, query.cursor());
```

Catch typed gateway, mapping, archive, and ERP failures, persist only a sanitized failure code, mark the batch failed, and rethrow. The scheduler acquires a shop-and-capability lease so overlapping invocations cannot process the same shop concurrently.

- [ ] **Step 4: Run integration and reactor tests**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am verify && mvn -B test`

Expected: duplicate pages do not create duplicate ERP orders; partial failures retain the old cursor; retry begins from the overlapped confirmed window.

- [ ] **Step 5: Commit order polling**

```bash
git add backend/erp-pinduoduo-connector
git commit -m "feat: pull pinduoduo orders safely"
```

### Task 8: Deduplicate Messages and Trigger Targeted Order Queries

**Files:**
- Create: `backend/erp-pinduoduo-connector/src/main/resources/db/migration/V103__pdd_messages.sql`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/messages/PddMessageController.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/messages/PddMessageService.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/messages/MessageSignatureVerifier.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/messages/PddMessageServiceIT.java`

**Interfaces:**
- Consumes: verified callback envelope `(shopId, messageId, eventType, businessKey, occurredAt, rawBody)`.
- Produces: archived message and a targeted order-query request; repeated or out-of-order messages remain harmless.

- [ ] **Step 1: Write duplicate, invalid-signature, and out-of-order tests**

```java
@Test
void duplicate_message_triggers_one_query() {
  messages.accept(envelope("M-1", "PO-1", t2));
  messages.accept(envelope("M-1", "PO-1", t2));
  verify(targetedQueries, times(1)).enqueue("SHOP-1", "PO-1");
}

@Test
void invalid_signature_is_rejected_before_archiving() {
  assertThatThrownBy(() -> controller.accept("bad-signature", rawBody))
      .isInstanceOf(InvalidMessageSignatureException.class);
  assertThat(messageCount()).isZero();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -Dtest=PddMessageServiceIT test`

Expected: callback and deduplication types are missing.

- [ ] **Step 3: Add message deduplication schema**

```sql
CREATE TABLE pdd_inbound_message (
  id VARCHAR(26) PRIMARY KEY, shop_id VARCHAR(26) NOT NULL,
  platform_message_id VARCHAR(128) NOT NULL, event_type VARCHAR(64) NOT NULL,
  business_key VARCHAR(160) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL,
  payload_ref VARCHAR(512) NOT NULL, payload_sha256 CHAR(64) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL,
  UNIQUE(shop_id, platform_message_id)
);
```

- [ ] **Step 4: Implement verify-archive-deduplicate-trigger flow**

`MessageSignatureVerifier` is a port. Simulator tests inject a deterministic verifier. A credential-backed verifier is activated only after the exact callback signature contract is obtained from the authenticated console.

- [ ] **Step 5: Run message and reactor tests**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am verify && mvn -B test`

Expected: invalid signatures archive nothing, duplicates trigger once, and older messages never overwrite or delete newer ERP state.

- [ ] **Step 6: Commit message intake**

```bash
git add backend/erp-pinduoduo-connector
git commit -m "feat: ingest pinduoduo order messages"
```

### Task 9: Add Readiness, Metrics, CI, and the Simulated Acceptance Gate

**Files:**
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/ops/PddConnectorMetrics.java`
- Create: `backend/erp-pinduoduo-connector/src/main/java/com/company/erp/pinduoduo/ops/PddReadinessIndicator.java`
- Test: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/ops/PddObservabilityIT.java`
- Create: `backend/erp-pinduoduo-connector/src/test/java/com/company/erp/pinduoduo/PddOrderIntakeE2EIT.java`
- Modify: `.github/workflows/ci.yml`
- Create: `docs/runbooks/pinduoduo-platform-orders.md`

**Interfaces:**
- Consumes: authorization state, production gate, last successful order cursor, failed batches, and simulator result.
- Produces: health component `pinduoduoReadiness`; metrics `pdd_order_pull_total`, `pdd_order_pull_lag_seconds`, `pdd_cursor_stalled_seconds`, `pdd_gateway_failure_total`, `pdd_unknown_state_total`.

- [ ] **Step 1: Write observability and end-to-end tests**

```java
@Test
void readiness_is_not_live_when_production_gate_is_closed() {
  assertThat(health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
  assertThat(health().getDetails()).doesNotContainKeys("token", "receiver", "rawPayload");
}

@Test
void simulated_paid_order_reaches_erp_once_after_retry() {
  simulator.enqueuePage(page("PO-100", null));
  pulls.pull("SHOP-1", now);
  pulls.pull("SHOP-1", now.plusSeconds(60));
  assertThat(erp.orders("PINDUODUO", "SHOP-1", "PO-100")).hasSize(1);
}
```

- [ ] **Step 2: Run tests and verify metrics/readiness are absent**

Run: `cd backend && mvn -pl erp-pinduoduo-connector -am -Dtest=PddObservabilityIT,PddOrderIntakeE2EIT test`

Expected: metric lookup and readiness component assertions fail.

- [ ] **Step 3: Register low-cardinality metrics and readiness details**

Only `platform`, internal `shopId`, `capability`, `result`, and failure category are permitted metric tags. Readiness reports booleans and timestamps but never authorization material, platform order IDs, receiver data, or raw payloads.

- [ ] **Step 4: Add CI and operational runbook**

CI runs:

```bash
cd backend
mvn -B verify
```

The runbook documents environment variables, simulator startup, authorization readiness, cursor inspection, failed-batch replay with the original idempotency identity, production-gate behavior, and the explicit statement that real production activation remains prohibited until all four Pinduoduo implementation plans and the unified launch gate pass.

- [ ] **Step 5: Run the complete acceptance suite**

Run: `cd backend && mvn -B verify`

Expected: all core and connector tests pass with MySQL Testcontainers; the end-to-end fixture archives the payload, submits one normalized order despite repeated pulls, and advances the cursor only after success.

- [ ] **Step 6: Commit the platform-and-order acceptance gate**

```bash
git add backend/erp-pinduoduo-connector .github/workflows/ci.yml docs/runbooks/pinduoduo-platform-orders.md
git commit -m "chore: verify pinduoduo order intake"
```

## Completion Gate

Before starting the Pinduoduo product-and-inventory plan, verify:

1. `cd backend && mvn -B verify` passes from a clean checkout with Docker running.
2. The standalone connector starts with production mode disabled.
3. Production order pulls and every platform side effect are rejected while the gate is disabled.
4. Authorization tokens and receiver details are encrypted and absent from logs, metrics, health, and error responses.
5. Duplicate pages, duplicate messages, repeated polling, and repeated ERP submissions create one ERP order.
6. A failure on any page leaves the prior cursor unchanged, and retry uses the configured overlap window.
7. Unknown states and malformed payloads enter a sanitized failure record instead of a normal order state.
8. Cross-shop internal API submissions are rejected.
9. The simulator covers success, authorization expiry, throttling, timeout, duplicate page, partial batch failure, and unknown state.
10. The production gate remains disabled because real Pinduoduo credentials and contract regression are not yet available.

