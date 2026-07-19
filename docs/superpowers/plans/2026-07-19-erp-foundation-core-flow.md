# ERP Foundation and Core Order Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-shaped vertical slice that receives a normalized platform order, maps it to an internal SKU, idempotently creates the ERP order, transactionally locks inventory, emits an Outbox event, creates a fulfillment order, and exposes the result in an authenticated admin UI.

**Architecture:** Use a Java 21/Spring Boot 3 modular monolith for core ERP domains and keep platform-specific code behind connector contracts. MySQL owns transactional truth, RocketMQ carries asynchronous events, Redis is limited to cache and short-lived coordination, and Vue 3 provides the admin UI. This is subproject 1 of the approved ERP program; subsequent subprojects extend the stable contracts created here.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Maven 3.9+, Spring Modulith, Flyway, MySQL 8, Redis 7, RocketMQ 5, Testcontainers, JUnit 5, AssertJ, ArchUnit, Vue 3, TypeScript, Vite, Pinia, Vitest, Playwright, Docker Compose.

## Global Constraints

- Support 9 domestic platforms: WeChat Video Account Store, Tmall, Taobao, JD, Pinduoduo, Douyin, Xiaohongshu, Kuaishou Store, and Dewu.
- Design for 1,000–10,000 average daily orders and approximately 30,000 peak-event orders; load tests use 2× the expected peak intake rate.
- ERP is the source of truth for internal SKU mapping, sales orders, inventory, fulfillment, and operating accounting.
- Inventory changes only through immutable ledger entries; business code must never directly overwrite inventory balances.
- External requests and message consumers are idempotent; core writes and Outbox records commit in the same MySQL transaction.
- Personal delivery data is encrypted at rest and masked by default; secrets and platform tokens never enter source control or normal logs.
- The core remains a modular monolith; modules may call public application services or publish events but may not mutate another module's tables.
- Production recovery targets are RPO ≤ 5 minutes and RTO ≤ 2 hours.
- Use test-first changes, run focused tests before full tests, and create one reviewable commit per task.

## Program Plan Sequence

This plan is the first independently runnable subproject. Later planning sessions create these exact plan artifacts in order after the preceding contracts are stable:

1. `2026-07-19-erp-foundation-core-flow.md` — engineering foundation and normalized order-to-fulfillment slice.
2. `2026-07-19-erp-first-platform-connectors.md` — Taobao/Tmall, JD, Pinduoduo, and Douyin connectors.
3. `2026-07-19-erp-wms-cloudwarehouse-dropship.md` — self-operated WMS, cloud warehouse, and supplier dropship.
4. `2026-07-19-erp-second-platform-connectors.md` — WeChat Store, Xiaohongshu, and Kuaishou connectors.
5. `2026-07-19-erp-aftersales-procurement.md` — returns, refunds, exchanges, purchasing, and supplier settlement.
6. `2026-07-19-erp-dewu-connector.md` — Dewu-specific listing and fulfillment flow.
7. `2026-07-19-erp-platform-finance-reconciliation.md` — platform bills, order profit, and Kingdee/Yonyou export.
8. `2026-07-19-erp-carrier-reconciliation.md` — carrier contracts, billed weight, invoice matching, disputes, and payable settlement.
9. `2026-07-19-erp-hardening-cutover.md` — security audit, load test, disaster recovery, operational runbooks, and phased cutover.

## Target File Structure

```text
backend/
  pom.xml                              # Maven reactor and dependency versions
  erp-boot/                            # Spring Boot entry point and runtime wiring
  erp-shared-kernel/                   # IDs, money, domain errors, clock, event envelope
  erp-identity/                        # Users, roles, JWT authentication, data scopes
  erp-masterdata/                      # Shops, warehouses, SKUs, channel mappings
  erp-order/                           # Raw orders, normalized sales orders, import idempotency
  erp-inventory/                       # Balances, immutable ledger, atomic reservations
  erp-fulfillment/                     # Routing result and fulfillment order lifecycle
  erp-outbox/                          # Transactional event persistence and publisher
  erp-connector-contracts/             # Stable connector commands and normalized DTOs
frontend/
  package.json                         # pnpm workspace commands
  apps/admin/                          # Vue admin application
infra/
  compose.yaml                         # MySQL, Redis, RocketMQ, MinIO, telemetry dependencies
  mysql/init/                          # Local database initialization
docs/adr/                              # Architecture decisions
.github/workflows/ci.yml               # Backend/frontend verification
```

---

### Task 1: Bootstrap the Repository and Enforce Module Boundaries

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/erp-shared-kernel/pom.xml`
- Create: `backend/erp-connector-contracts/pom.xml`
- Create: `backend/erp-masterdata/pom.xml`
- Create: `backend/erp-order/pom.xml`
- Create: `backend/erp-inventory/pom.xml`
- Create: `backend/erp-outbox/pom.xml`
- Create: `backend/erp-fulfillment/pom.xml`
- Create: `backend/erp-identity/pom.xml`
- Create: `backend/erp-boot/pom.xml`
- Create: `backend/erp-boot/src/main/java/com/company/erp/ErpApplication.java`
- Create: `backend/erp-boot/src/test/java/com/company/erp/ModuleBoundaryTest.java`
- Create: `infra/compose.yaml`
- Create: `docs/adr/0001-modular-monolith.md`

**Interfaces:**
- Consumes: none.
- Produces: Maven modules named in the target structure; executable `com.company.erp.ErpApplication`; local MySQL on `3306`, Redis on `6379`, RocketMQ broker on `10911`, and MinIO on `9000`.

- [ ] **Step 1: Write the architecture test before creating domain modules**

```java
package com.company.erp;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ModuleBoundaryTest {
  @Test
  void modules_do_not_access_other_module_internals() {
    var classes = new ClassFileImporter().importPackages("com.company.erp");
    noClasses().that().resideOutsideOfPackage("..order..")
        .should().dependOnClassesThat().resideInAPackage("..order.internal..")
        .check(classes);
  }
}
```

- [ ] **Step 2: Create the Maven reactor and minimal Boot application**

```xml
<!-- backend/pom.xml -->
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.company.erp</groupId><artifactId>erp-parent</artifactId><version>0.1.0-SNAPSHOT</version><packaging>pom</packaging>
  <properties><maven.compiler.release>21</maven.compiler.release><spring-boot.version>3.5.3</spring-boot.version></properties>
  <dependencyManagement><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>${spring-boot.version}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
  <build><pluginManagement><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.3</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.3</version></plugin>
  </plugins></pluginManagement></build>
  <modules>
    <module>erp-shared-kernel</module><module>erp-connector-contracts</module><module>erp-masterdata</module>
    <module>erp-order</module><module>erp-inventory</module><module>erp-outbox</module>
    <module>erp-fulfillment</module><module>erp-identity</module><module>erp-boot</module>
  </modules>
</project>
```

Create each library module POM with the following complete shape and its directory's artifact ID (`erp-shared-kernel`, `erp-connector-contracts`, `erp-masterdata`, `erp-order`, `erp-inventory`, `erp-outbox`, `erp-fulfillment`, or `erp-identity`):

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>com.company.erp</groupId><artifactId>erp-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>erp-shared-kernel</artifactId>
  <dependencies>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

Create `backend/erp-boot/pom.xml` with the application and architecture-test dependencies:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>com.company.erp</groupId><artifactId>erp-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>erp-boot</artifactId>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><version>1.4.1</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId><version>${spring-boot.version}</version></plugin></plugins></build>
</project>
```

```java
package com.company.erp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication public class ErpApplication {
  public static void main(String[] args) { SpringApplication.run(ErpApplication.class, args); }
}
```

- [ ] **Step 3: Add local infrastructure with persistent named volumes**

```yaml
services:
  mysql:
    image: mysql:8.4
    environment: {MYSQL_DATABASE: erp, MYSQL_USER: erp, MYSQL_PASSWORD: erp_local, MYSQL_ROOT_PASSWORD: root_local}
    ports: ["3306:3306"]
    volumes: ["mysql_data:/var/lib/mysql"]
  redis:
    image: redis:7.4-alpine
    ports: ["6379:6379"]
  namesrv:
    image: apache/rocketmq:5.3.1
    command: sh mqnamesrv
    ports: ["9876:9876"]
  broker:
    image: apache/rocketmq:5.3.1
    command: sh mqbroker -n namesrv:9876
    environment: {NAMESRV_ADDR: "namesrv:9876"}
    depends_on: [namesrv]
    ports: ["10911:10911", "10909:10909"]
  minio:
    image: minio/minio:RELEASE.2025-04-22T22-12-26Z
    command: server /data --console-address :9001
    environment: {MINIO_ROOT_USER: erp_local, MINIO_ROOT_PASSWORD: erp_local_secret}
    ports: ["9000:9000", "9001:9001"]
volumes: {mysql_data: {}}
```

- [ ] **Step 4: Run boundary and boot tests**

Run: `cd backend && mvn -B test`

Expected: reactor ends with `BUILD SUCCESS`; `ModuleBoundaryTest` passes.

- [ ] **Step 5: Commit the bootstrap**

```bash
git add backend infra docs/adr/0001-modular-monolith.md
git commit -m "build: bootstrap modular ERP workspace"
```

### Task 2: Create Shared Domain Primitives and Error Contract

**Files:**
- Modify: `backend/erp-shared-kernel/pom.xml`
- Create: `backend/erp-shared-kernel/src/main/java/com/company/erp/shared/Ids.java`
- Create: `backend/erp-shared-kernel/src/main/java/com/company/erp/shared/DomainError.java`
- Create: `backend/erp-shared-kernel/src/main/java/com/company/erp/shared/EventEnvelope.java`
- Test: `backend/erp-shared-kernel/src/test/java/com/company/erp/shared/SharedKernelTest.java`

**Interfaces:**
- Consumes: Java `Clock`, `Instant`, and `SecureRandom`.
- Produces: `Ids.newId()`, `DomainError(code, message)`, and `EventEnvelope<T>(eventId, eventType, aggregateId, occurredAt, payload)`.

- [ ] **Step 1: Write failing tests for stable IDs and event metadata**

```java
@Test void creates_non_blank_ulid_style_id() {
  assertThat(Ids.newId()).matches("[0-9A-HJKMNP-TV-Z]{26}");
}
@Test void event_envelope_keeps_business_identity() {
  var event = EventEnvelope.create("OrderImported", "SO-1", Map.of("shopId", "S-1"), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  assertThat(event.aggregateId()).isEqualTo("SO-1");
  assertThat(event.occurredAt()).isEqualTo(Instant.EPOCH);
}
```

- [ ] **Step 2: Run the focused test and verify failure**

Run: `cd backend && mvn -pl erp-shared-kernel -Dtest=SharedKernelTest test`

Expected: compilation fails because `Ids` and `EventEnvelope` do not exist.

- [ ] **Step 3: Implement immutable records and monotonic ID generation**

```java
public record DomainError(String code, String message) {
  public DomainError { if (code.isBlank() || message.isBlank()) throw new IllegalArgumentException("code and message are required"); }
}
public record EventEnvelope<T>(String eventId, String eventType, String aggregateId, Instant occurredAt, T payload) {
  public static <T> EventEnvelope<T> create(String type, String aggregateId, T payload, Clock clock) {
    return new EventEnvelope<>(Ids.newId(), type, aggregateId, clock.instant(), payload);
  }
}
```

```java
public final class Ids {
  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();
  private Ids() {}
  public static String newId() {
    var value = new char[26];
    for (int i = 0; i < value.length; i++) value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    return new String(value);
  }
}
```

- [ ] **Step 4: Run shared-kernel and reactor tests**

Run: `cd backend && mvn -pl erp-shared-kernel test && mvn -B test`

Expected: both commands end with `BUILD SUCCESS`.

- [ ] **Step 5: Commit shared primitives**

```bash
git add backend/erp-shared-kernel
git commit -m "feat: add shared domain primitives"
```

### Task 3: Persist Shops, Warehouses, SKUs, and Channel SKU Mappings

**Files:**
- Modify: `backend/erp-masterdata/pom.xml`
- Create: `backend/erp-masterdata/src/main/resources/db/migration/V001__masterdata.sql`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/MasterDataService.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/ChannelSkuMapping.java`
- Test: `backend/erp-masterdata/src/test/java/com/company/erp/masterdata/MasterDataServiceIT.java`

**Interfaces:**
- Consumes: `platform`, `shopId`, `platformSkuId`.
- Produces: `Optional<String> MasterDataService.resolveInternalSku(String platform, String shopId, String platformSkuId)`.

- [ ] **Step 1: Write a Testcontainers integration test for channel mapping**

```java
@Test void resolves_platform_sku_to_internal_sku() {
  service.createSku("SKU-RED-M", "6900000000012", false, false);
  service.mapChannelSku("PINDUODUO", "SHOP-1", "PDD-SKU-9", "SKU-RED-M");
  assertThat(service.resolveInternalSku("PINDUODUO", "SHOP-1", "PDD-SKU-9")).contains("SKU-RED-M");
}
```

- [ ] **Step 2: Run the test and verify the schema/service is missing**

Run: `cd backend && mvn -pl erp-masterdata -Dtest=MasterDataServiceIT test`

Expected: compilation fails for missing `MasterDataService`.

- [ ] **Step 3: Add normalized master-data tables**

```sql
CREATE TABLE md_sku (id VARCHAR(26) PRIMARY KEY, sku_code VARCHAR(64) NOT NULL UNIQUE, barcode VARCHAR(64), batch_enabled BOOLEAN NOT NULL, serial_enabled BOOLEAN NOT NULL, created_at TIMESTAMP(6) NOT NULL);
CREATE TABLE md_shop (id VARCHAR(26) PRIMARY KEY, platform VARCHAR(32) NOT NULL, shop_code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL, UNIQUE(platform, shop_code));
CREATE TABLE md_warehouse (id VARCHAR(26) PRIMARY KEY, warehouse_code VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(128) NOT NULL, fulfillment_type VARCHAR(32) NOT NULL);
CREATE TABLE md_channel_sku_mapping (id VARCHAR(26) PRIMARY KEY, platform VARCHAR(32) NOT NULL, shop_id VARCHAR(26) NOT NULL, platform_sku_id VARCHAR(128) NOT NULL, internal_sku_code VARCHAR(64) NOT NULL, UNIQUE(platform, shop_id, platform_sku_id));
```

- [ ] **Step 4: Implement mapping lookup and pass integration tests**

```java
public interface MasterDataService {
  void createSku(String skuCode, String barcode, boolean batchEnabled, boolean serialEnabled);
  void mapChannelSku(String platform, String shopId, String platformSkuId, String internalSkuCode);
  Optional<String> resolveInternalSku(String platform, String shopId, String platformSkuId);
}
```

Run: `cd backend && mvn -pl erp-masterdata test`

Expected: `MasterDataServiceIT` passes against MySQL 8 Testcontainer.

- [ ] **Step 5: Commit master data**

```bash
git add backend/erp-masterdata
git commit -m "feat: add SKU and channel mapping master data"
```

### Task 4: Define the Connector Contract and Raw Order Archive

**Files:**
- Modify: `backend/erp-connector-contracts/pom.xml`
- Modify: `backend/erp-order/pom.xml`
- Create: `backend/erp-connector-contracts/src/main/java/com/company/erp/connectors/NormalizedOrder.java`
- Create: `backend/erp-connector-contracts/src/main/java/com/company/erp/connectors/OrderIngestionPort.java`
- Create: `backend/erp-order/src/main/resources/db/migration/V002__raw_orders.sql`
- Create: `backend/erp-order/src/main/java/com/company/erp/order/RawOrderArchive.java`
- Test: `backend/erp-order/src/test/java/com/company/erp/order/RawOrderArchiveIT.java`

**Interfaces:**
- Consumes: `NormalizedOrder(platform, shopId, platformOrderId, paidAt, receiverCiphertext, lines, rawPayloadRef)`.
- Produces: `OrderIngestionPort.ingest(NormalizedOrder)` returning `IngestionResult(orderId, status, errorCode)`.

- [ ] **Step 1: Write a failing archive/idempotency test**

```java
@Test void stores_each_raw_payload_version_but_one_business_identity() {
  archive.append("PINDUODUO", "SHOP-1", "PO-100", "oss://raw/a.json", "sha256-a");
  archive.append("PINDUODUO", "SHOP-1", "PO-100", "oss://raw/b.json", "sha256-b");
  assertThat(archive.versions("PINDUODUO", "SHOP-1", "PO-100")).hasSize(2);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `cd backend && mvn -pl erp-order -am -Dtest=RawOrderArchiveIT test`

Expected: compilation fails because archive types do not exist.

- [ ] **Step 3: Define connector DTOs without platform-specific fields**

```java
public record NormalizedOrder(String platform, String shopId, String platformOrderId, Instant paidAt,
  String receiverCiphertext, List<Line> lines, String rawPayloadRef) {
  public record Line(String platformSkuId, int quantity, long paidAmountFen) {}
}
public interface OrderIngestionPort { IngestionResult ingest(NormalizedOrder order); }
public record IngestionResult(String orderId, String status, String errorCode) {}
```

- [ ] **Step 4: Add append-only raw order schema and implementation**

```sql
CREATE TABLE ord_raw_order_version (id VARCHAR(26) PRIMARY KEY, platform VARCHAR(32) NOT NULL, shop_id VARCHAR(26) NOT NULL, platform_order_id VARCHAR(128) NOT NULL, payload_ref VARCHAR(512) NOT NULL, payload_sha256 CHAR(64) NOT NULL, received_at TIMESTAMP(6) NOT NULL, UNIQUE(platform, shop_id, platform_order_id, payload_sha256));
```

Run: `cd backend && mvn -pl erp-order -am test`

Expected: archive test passes and duplicate payload hash is rejected idempotently.

- [ ] **Step 5: Commit connector contract and archive**

```bash
git add backend/erp-connector-contracts backend/erp-order
git commit -m "feat: define normalized order connector contract"
```

### Task 5: Import Sales Orders Idempotently and Quarantine Unmapped SKUs

**Files:**
- Modify: `backend/erp-order/pom.xml`
- Create: `backend/erp-order/src/main/resources/db/migration/V003__sales_orders.sql`
- Create: `backend/erp-order/src/main/java/com/company/erp/order/OrderIngestionService.java`
- Create: `backend/erp-order/src/main/java/com/company/erp/order/OrderQueryService.java`
- Test: `backend/erp-order/src/test/java/com/company/erp/order/OrderIngestionServiceIT.java`

**Interfaces:**
- Consumes: `MasterDataService.resolveInternalSku(...)`, `NormalizedOrder`.
- Produces: `IngestionResult`; `OrderQueryService.get(String orderId)` returning order header, lines, and exception state.

- [ ] **Step 1: Write failing tests for duplicate order and unmapped SKU**

```java
@Test void duplicate_platform_order_returns_same_erp_order() {
  var first = service.ingest(order("PO-100", "PDD-SKU-9"));
  var second = service.ingest(order("PO-100", "PDD-SKU-9"));
  assertThat(second.orderId()).isEqualTo(first.orderId());
}
@Test void unmapped_sku_is_quarantined_without_inventory_action() {
  var result = service.ingest(order("PO-101", "UNKNOWN"));
  assertThat(result).extracting(IngestionResult::status, IngestionResult::errorCode)
      .containsExactly("EXCEPTION", "SKU_NOT_MAPPED");
}
```

- [ ] **Step 2: Verify both tests fail**

Run: `cd backend && mvn -pl erp-order -am -Dtest=OrderIngestionServiceIT test`

Expected: failure for missing sales-order schema/service.

- [ ] **Step 3: Add order header, line, and exception tables**

```sql
CREATE TABLE ord_sales_order (id VARCHAR(26) PRIMARY KEY, platform VARCHAR(32) NOT NULL, shop_id VARCHAR(26) NOT NULL, platform_order_id VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL, paid_at TIMESTAMP(6) NOT NULL, receiver_ciphertext TEXT NOT NULL, version BIGINT NOT NULL DEFAULT 0, UNIQUE(platform, shop_id, platform_order_id));
CREATE TABLE ord_sales_order_line (id VARCHAR(26) PRIMARY KEY, order_id VARCHAR(26) NOT NULL, platform_sku_id VARCHAR(128) NOT NULL, internal_sku_code VARCHAR(64), quantity INT NOT NULL, paid_amount_fen BIGINT NOT NULL);
CREATE TABLE ord_exception (id VARCHAR(26) PRIMARY KEY, order_id VARCHAR(26) NOT NULL, error_code VARCHAR(64) NOT NULL, detail VARCHAR(512) NOT NULL, status VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL);
```

- [ ] **Step 4: Implement transaction and pass tests**

```java
@Transactional
public IngestionResult ingest(NormalizedOrder input) {
  return repository.findIdentity(input.platform(), input.shopId(), input.platformOrderId())
      .map(id -> new IngestionResult(id, repository.status(id), ""))
      .orElseGet(() -> createAndMap(input));
}
```

Run: `cd backend && mvn -pl erp-order -am test`

Expected: duplicate and unmapped-SKU tests pass; database has one header for `PO-100`.

- [ ] **Step 5: Commit order ingestion**

```bash
git add backend/erp-order
git commit -m "feat: ingest sales orders idempotently"
```

### Task 6: Add Immutable Inventory Ledger and Atomic Reservation

**Files:**
- Modify: `backend/erp-inventory/pom.xml`
- Create: `backend/erp-inventory/src/main/resources/db/migration/V004__inventory.sql`
- Create: `backend/erp-inventory/src/main/java/com/company/erp/inventory/InventoryService.java`
- Create: `backend/erp-inventory/src/main/java/com/company/erp/inventory/ReservationResult.java`
- Test: `backend/erp-inventory/src/test/java/com/company/erp/inventory/InventoryConcurrencyIT.java`

**Interfaces:**
- Consumes: `reserve(orderId, warehouseId, skuCode, quantity)`.
- Produces: `ReservationResult(reservationId, status, availableAfter)`; ledger reason codes `PURCHASE_RECEIPT`, `ORDER_RESERVE`, `ORDER_RELEASE`, `SALES_SHIPMENT`, `RETURN_RECEIPT`, `STOCK_ADJUSTMENT`.

- [ ] **Step 1: Write a concurrent reservation test**

```java
@Test void only_one_of_two_competing_orders_can_reserve_last_unit() throws Exception {
  service.receive("WH-1", "SKU-RED-M", 1, "RECEIPT-1");
  var results = runConcurrently(
      () -> service.reserve("SO-1", "WH-1", "SKU-RED-M", 1),
      () -> service.reserve("SO-2", "WH-1", "SKU-RED-M", 1));
  assertThat(results).filteredOn(r -> r.status().equals("RESERVED")).hasSize(1);
  assertThat(results).filteredOn(r -> r.status().equals("INSUFFICIENT")).hasSize(1);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `cd backend && mvn -pl erp-inventory -am -Dtest=InventoryConcurrencyIT test`

Expected: compilation fails for missing inventory service.

- [ ] **Step 3: Add balance, ledger, and reservation schema**

```sql
CREATE TABLE inv_balance (warehouse_id VARCHAR(26) NOT NULL, sku_code VARCHAR(64) NOT NULL, sellable_qty INT NOT NULL, reserved_qty INT NOT NULL, version BIGINT NOT NULL, PRIMARY KEY(warehouse_id, sku_code));
CREATE TABLE inv_ledger (id VARCHAR(26) PRIMARY KEY, warehouse_id VARCHAR(26) NOT NULL, sku_code VARCHAR(64) NOT NULL, quantity_delta INT NOT NULL, balance_type VARCHAR(32) NOT NULL, reason_code VARCHAR(32) NOT NULL, source_id VARCHAR(64) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL, UNIQUE(reason_code, source_id, warehouse_id, sku_code));
CREATE TABLE inv_reservation (id VARCHAR(26) PRIMARY KEY, order_id VARCHAR(26) NOT NULL, warehouse_id VARCHAR(26) NOT NULL, sku_code VARCHAR(64) NOT NULL, quantity INT NOT NULL, status VARCHAR(32) NOT NULL, UNIQUE(order_id, warehouse_id, sku_code));
```

- [ ] **Step 4: Implement conditional update and ledger write in one transaction**

```java
@Transactional
public ReservationResult reserve(String orderId, String warehouseId, String sku, int qty) {
  int changed = balanceRepository.reserveIfAvailable(warehouseId, sku, qty);
  if (changed == 0) return ReservationResult.insufficient();
  var id = reservationRepository.insert(orderId, warehouseId, sku, qty);
  ledgerRepository.append(warehouseId, sku, -qty, "SELLABLE", "ORDER_RESERVE", orderId);
  return ReservationResult.reserved(id, balanceRepository.available(warehouseId, sku));
}
```

Run: `cd backend && mvn -pl erp-inventory -am test`

Expected: the concurrency test passes repeatedly with one reservation and no negative sellable quantity.

- [ ] **Step 5: Commit inventory reservation**

```bash
git add backend/erp-inventory
git commit -m "feat: add atomic inventory reservations"
```

### Task 7: Persist and Publish Transactional Outbox Events

**Files:**
- Modify: `backend/erp-outbox/pom.xml`
- Create: `backend/erp-outbox/src/main/resources/db/migration/V005__outbox.sql`
- Create: `backend/erp-outbox/src/main/java/com/company/erp/outbox/OutboxService.java`
- Create: `backend/erp-outbox/src/main/java/com/company/erp/outbox/OutboxBroker.java`
- Create: `backend/erp-outbox/src/main/java/com/company/erp/outbox/OutboxPublisher.java`
- Test: `backend/erp-outbox/src/test/java/com/company/erp/outbox/OutboxServiceIT.java`

**Interfaces:**
- Consumes: `OutboxService.append(EventEnvelope<?>)` in the caller's transaction.
- Produces: RocketMQ messages with headers `eventId`, `eventType`, `aggregateId`; marks rows published only after broker acknowledgement.

- [ ] **Step 1: Write rollback and publish-ack tests**

```java
@Test void rolled_back_business_transaction_has_no_outbox_row() {
  assertThatThrownBy(() -> fixture.reserveThenFail()).isInstanceOf(IllegalStateException.class);
  assertThat(repository.count()).isZero();
}
@Test void publisher_marks_row_only_after_ack() {
  publisher.publishBatch();
  verify(broker).send(any());
  assertThat(repository.findPending()).isEmpty();
}
```

- [ ] **Step 2: Run and verify failure**

Run: `cd backend && mvn -pl erp-outbox -am -Dtest=OutboxServiceIT test`

Expected: missing schema and publisher types.

- [ ] **Step 3: Add Outbox schema with retry metadata**

```sql
CREATE TABLE sys_outbox (id VARCHAR(26) PRIMARY KEY, event_type VARCHAR(128) NOT NULL, aggregate_id VARCHAR(64) NOT NULL, payload_json JSON NOT NULL, status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP(6) NOT NULL, created_at TIMESTAMP(6) NOT NULL, published_at TIMESTAMP(6), INDEX idx_outbox_pending(status, next_attempt_at));
```

- [ ] **Step 4: Implement claim-send-ack with bounded exponential backoff**

```java
public interface OutboxBroker {
  void send(OutboxMessage message);
}

public void publishBatch() {
  repository.claimPending(100).forEach(row -> {
    try { broker.send(row.toMessage()); repository.markPublished(row.id(), clock.instant()); }
    catch (RuntimeException ex) { repository.reschedule(row.id(), Math.min(3600, 1 << Math.min(row.attempts(), 11))); }
  });
}
```

Run: `cd backend && mvn -pl erp-outbox -am test`

Expected: rollback and broker-ack tests pass.

- [ ] **Step 5: Commit Outbox support**

```bash
git add backend/erp-outbox
git commit -m "feat: publish transactional outbox events"
```

### Task 8: Orchestrate Order Import, Inventory Lock, and Fulfillment Creation

**Files:**
- Modify: `backend/erp-fulfillment/pom.xml`
- Modify: `backend/erp-boot/pom.xml`
- Create: `backend/erp-fulfillment/src/main/resources/db/migration/V006__fulfillment.sql`
- Create: `backend/erp-fulfillment/src/main/java/com/company/erp/fulfillment/FulfillmentService.java`
- Create: `backend/erp-fulfillment/src/main/java/com/company/erp/fulfillment/RoutingPolicy.java`
- Modify: `backend/erp-order/src/main/java/com/company/erp/order/OrderIngestionService.java`
- Test: `backend/erp-boot/src/test/java/com/company/erp/OrderToFulfillmentE2EIT.java`

**Interfaces:**
- Consumes: successfully mapped sales order, warehouse priority list, `InventoryService.reserve`.
- Produces: `FulfillmentOrder(id, salesOrderId, warehouseId, type, status)` and `FulfillmentCreated` Outbox event.

- [ ] **Step 1: Write the end-to-end business test**

```java
@Test void paid_order_becomes_reserved_fulfillment() {
  fixture.sku("SKU-1").mappedFrom("PINDUODUO", "SHOP-1", "PDD-1");
  fixture.stock("WH-1", "SKU-1", 5);
  var result = ingestion.ingest(fixture.order("PO-1", "PDD-1", 2));
  assertThat(result.status()).isEqualTo("READY_TO_FULFILL");
  assertThat(fulfillment.findByOrder(result.orderId())).singleElement()
      .extracting("warehouseId", "status").containsExactly("WH-1", "RESERVED");
  assertThat(inventory.available("WH-1", "SKU-1")).isEqualTo(3);
  assertThat(outbox.pendingTypes()).contains("FulfillmentCreated");
}
```

- [ ] **Step 2: Run and verify failure before orchestration exists**

Run: `cd backend && mvn -pl erp-boot -am -Dtest=OrderToFulfillmentE2EIT test`

Expected: order imports but no fulfillment row or Outbox event exists.

- [ ] **Step 3: Add fulfillment schema and deterministic routing policy**

```sql
CREATE TABLE ful_order (id VARCHAR(26) PRIMARY KEY, sales_order_id VARCHAR(26) NOT NULL, warehouse_id VARCHAR(26) NOT NULL, fulfillment_type VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL, UNIQUE(sales_order_id, warehouse_id));
```

```java
public interface RoutingPolicy {
  List<String> candidateWarehouses(String shopId, String receiverRegionCode, List<String> skuCodes);
}
```

- [ ] **Step 4: Wire the transaction and pass E2E test**

```java
@Transactional
public IngestionResult createMappedOrderAndFulfillment(NormalizedOrder input) {
  var order = orderCreator.create(input);
  var warehouse = routing.candidateWarehouses(input.shopId(), region(input), order.skuCodes()).stream()
      .filter(id -> inventory.canReserveAll(id, order.lines())).findFirst().orElseThrow(NoStockException::new);
  inventory.reserveAll(order.id(), warehouse, order.lines());
  var fulfillmentId = fulfillment.create(order.id(), warehouse, "SELF_WAREHOUSE", "RESERVED");
  outbox.append(EventEnvelope.create("FulfillmentCreated", fulfillmentId, Map.of("orderId", order.id()), clock));
  return new IngestionResult(order.id(), "READY_TO_FULFILL", "");
}
```

Run: `cd backend && mvn -pl erp-boot -am test`

Expected: full reactor slice passes; rollback leaves neither reservations nor fulfillment rows.

- [ ] **Step 5: Commit the vertical slice**

```bash
git add backend/erp-order backend/erp-inventory backend/erp-fulfillment backend/erp-outbox backend/erp-boot
git commit -m "feat: create fulfillment from imported orders"
```

### Task 9: Add JWT Authentication and Read-Only Admin APIs

**Files:**
- Modify: `backend/erp-identity/pom.xml`
- Modify: `backend/erp-boot/pom.xml`
- Create: `backend/erp-identity/src/main/resources/db/migration/V007__identity.sql`
- Create: `backend/erp-identity/src/main/java/com/company/erp/identity/SecurityConfiguration.java`
- Create: `backend/erp-boot/src/main/java/com/company/erp/api/OrderController.java`
- Create: `backend/erp-boot/src/main/java/com/company/erp/api/ApiExceptionHandler.java`
- Test: `backend/erp-boot/src/test/java/com/company/erp/api/OrderControllerIT.java`

**Interfaces:**
- Consumes: bearer JWT with claims `sub`, `roles`, `shopIds`, `warehouseIds`.
- Produces: `GET /api/orders/{id}` and `GET /api/orders?status=&platform=&page=`; receiver fields are masked unless role includes `PII_VIEW`.

- [ ] **Step 1: Write unauthorized, scoped, and masking tests**

```java
@Test void request_without_token_is_unauthorized() { get("/api/orders/SO-1").andExpect(status().isUnauthorized()); }
@Test void operator_sees_masked_receiver() {
  getWithJwt("/api/orders/SO-1", "ORDER_VIEW").andExpect(jsonPath("$.receiverPhone").value("138****0000"));
}
@Test void operator_cannot_read_order_from_unassigned_shop() {
  getWithJwtAndShops("/api/orders/SO-2", List.of("SHOP-1")).andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run and verify endpoints are absent**

Run: `cd backend && mvn -pl erp-boot -am -Dtest=OrderControllerIT test`

Expected: 404 or missing controller before implementation.

- [ ] **Step 3: Configure stateless resource-server security**

```java
@Bean SecurityFilterChain api(HttpSecurity http) throws Exception {
  return http.csrf(AbstractHttpConfigurer::disable)
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
      .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())).build();
}
```

- [ ] **Step 4: Implement scoped query and masking, then run tests**

```java
@GetMapping("/api/orders/{id}")
OrderView get(@PathVariable String id, JwtAuthenticationToken auth) {
  var order = queries.getWithinShops(id, auth.getToken().getClaimAsStringList("shopIds"));
  return OrderView.from(order, auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PII_VIEW")));
}
```

Run: `cd backend && mvn -pl erp-boot -am test`

Expected: authentication, shop scope, and masking tests pass.

- [ ] **Step 5: Commit secure APIs**

```bash
git add backend/erp-identity backend/erp-boot
git commit -m "feat: secure order query APIs"
```

### Task 10: Build the Admin Order and Exception Workbench

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/pnpm-workspace.yaml`
- Create: `frontend/apps/admin/src/main.ts`
- Create: `frontend/apps/admin/src/api/orders.ts`
- Create: `frontend/apps/admin/src/views/orders/OrderListView.vue`
- Create: `frontend/apps/admin/src/views/orders/OrderDetailView.vue`
- Create: `frontend/apps/admin/src/views/exceptions/OrderExceptionView.vue`
- Test: `frontend/apps/admin/src/views/orders/OrderListView.spec.ts`
- Test: `frontend/apps/admin/e2e/order-flow.spec.ts`

**Interfaces:**
- Consumes: Task 9 order APIs and JWT from the configured identity provider.
- Produces: filterable order list, masked order detail, fulfillment status, and SKU-not-mapped exception queue.

- [ ] **Step 1: Write component test for filters and masked data**

```ts
it('loads filtered orders and keeps receiver masked', async () => {
  server.use(http.get('/api/orders', () => HttpResponse.json({items: [{id:'SO-1', platform:'PINDUODUO', receiverPhone:'138****0000', status:'READY_TO_FULFILL'}]})))
  render(OrderListView)
  await userEvent.selectOptions(screen.getByLabelText('平台'), 'PINDUODUO')
  expect(await screen.findByText('SO-1')).toBeVisible()
  expect(screen.getByText('138****0000')).toBeVisible()
})
```

- [ ] **Step 2: Run and verify component test fails**

Run: `cd frontend && pnpm install --frozen-lockfile=false && pnpm --filter admin test --run`

Expected: test fails because the view and API client are missing.

- [ ] **Step 3: Implement typed API client and order list**

```ts
export type OrderSummary = {id:string; platform:string; status:string; receiverPhone:string}
export async function listOrders(params: URLSearchParams): Promise<{items:OrderSummary[]}> {
  const response = await fetch(`/api/orders?${params}`, {headers:{Authorization:`Bearer ${tokenStore.token}`}})
  if (!response.ok) throw new Error(`ORDER_LIST_${response.status}`)
  return response.json()
}
```

- [ ] **Step 4: Add Playwright happy-path assertion and run all frontend tests**

```ts
test('operator opens a reserved fulfillment', async ({page}) => {
  await page.goto('/orders')
  await page.getByText('SO-1').click()
  await expect(page.getByText('已锁库存')).toBeVisible()
  await expect(page.getByText('138****0000')).toBeVisible()
})
```

Run: `cd frontend && pnpm --filter admin test --run && pnpm --filter admin e2e`

Expected: Vitest and Playwright both pass.

- [ ] **Step 5: Commit the workbench**

```bash
git add frontend
git commit -m "feat: add order operations workbench"
```

### Task 11: Add Observability, Recovery Checks, and CI Acceptance Gate

**Files:**
- Modify: `backend/erp-boot/pom.xml`
- Create: `backend/erp-boot/src/main/resources/application.yml`
- Create: `backend/erp-boot/src/main/java/com/company/erp/ops/OrderMetrics.java`
- Create: `backend/erp-boot/src/test/java/com/company/erp/ops/ObservabilityIT.java`
- Create: `infra/scripts/backup-restore-smoke.ps1`
- Create: `.github/workflows/ci.yml`
- Create: `docs/runbooks/core-order-flow.md`

**Interfaces:**
- Consumes: Micrometer/OTel, MySQL backup credentials from CI secrets.
- Produces: metrics `erp_order_ingestion_total`, `erp_order_ingestion_lag_seconds`, `erp_inventory_reservation_failure_total`, `erp_outbox_pending`; health endpoints and a backup-restore smoke command.

- [ ] **Step 1: Write metrics and health integration tests**

```java
@Test void failed_stock_reservation_increments_metric() {
  inventory.reserve("SO-9", "WH-1", "SKU-EMPTY", 1);
  assertThat(registry.get("erp_inventory_reservation_failure_total").counter().count()).isEqualTo(1.0);
}
@Test void health_does_not_expose_secrets() {
  assertThat(rest.getForObject("/actuator/health", String.class)).doesNotContain("password", "token", "receiver");
}
```

- [ ] **Step 2: Run tests and confirm metrics are absent**

Run: `cd backend && mvn -pl erp-boot -am -Dtest=ObservabilityIT test`

Expected: metric lookup fails before instrumentation.

- [ ] **Step 3: Register metrics and structured correlation fields**

```java
public final class OrderMetrics {
  private final Counter reservationFailures;
  public OrderMetrics(MeterRegistry registry) {
    this.reservationFailures = Counter.builder("erp.inventory.reservation.failure").register(registry);
  }
  public void reservationFailed() { reservationFailures.increment(); }
}
```

- [ ] **Step 4: Add full CI and backup-restore smoke verification**

```powershell
$ErrorActionPreference='Stop'
docker compose -f infra/compose.yaml exec -T mysql mysqldump -uerp -perp_local erp | Set-Content $env:TEMP\erp.sql
docker compose -f infra/compose.yaml exec -T mysql mysql -uroot -proot_local -e 'DROP DATABASE erp; CREATE DATABASE erp;'
Get-Content $env:TEMP\erp.sql | docker compose -f infra/compose.yaml exec -T mysql mysql -uerp -perp_local erp
if ($LASTEXITCODE -ne 0) { throw 'restore failed' }
```

Run: `docker compose -f infra/compose.yaml up -d && cd backend && mvn -B verify && cd ../frontend && pnpm test && powershell -File ../infra/scripts/backup-restore-smoke.ps1`

Expected: backend verify, frontend tests, and database restore all exit `0`.

- [ ] **Step 5: Commit the acceptance gate**

```bash
git add backend/erp-boot infra .github/workflows/ci.yml docs/runbooks/core-order-flow.md
git commit -m "chore: add core flow operational acceptance gate"
```

## Approved-Spec Coverage

| Approved specification area | Implemented here | Follow-on plan |
|---|---|---|
| Modular monolith, CI/CD baseline, security, observability | Tasks 1, 2, 9, 11 | Hardening/cutover deepens production controls |
| Shop/SKU/channel mapping | Task 3 | Platform connector plans add platform fields and synchronization |
| Raw order archive and unified order model | Tasks 4 and 5 | Each connector supplies its adapter and golden payloads |
| Inventory ledger, idempotency, Outbox | Tasks 6 and 7 | WMS plan adds batch, expiry, location, and serial-number operations |
| Routing and fulfillment creation | Task 8 | WMS/cloud warehouse/dropship plan executes fulfillment |
| Operations UI and exception workbench | Task 10 | Each later plan adds its own exception types and actions |
| Purchasing, aftersales, platform finance, carrier reconciliation, finance export | Stable IDs/events/APIs only | Dedicated plans 5, 7, and 8 in the program sequence |
| Nine production platform integrations | Connector contract only | Dedicated plans 2, 4, and 6 in the program sequence |

## Completion Gate for This Plan

Before starting the first real platform connector plan, verify all of the following:

1. `cd backend && mvn -B verify` passes from a clean checkout.
2. `cd frontend && pnpm install --frozen-lockfile && pnpm test` passes.
3. The end-to-end fixture imports the same order twice but creates one ERP order, one reservation, and one fulfillment order.
4. Two concurrent orders competing for one unit never produce negative inventory.
5. A forced transaction rollback leaves no sales order, reservation, fulfillment, or Outbox row.
6. The admin API rejects unauthenticated and out-of-scope access and masks delivery data.
7. Metrics expose ingestion lag, reservation failures, and Outbox backlog without exposing secrets or personal data.
8. The documented backup-restore smoke test succeeds.
9. `git status --short` is empty and every task has its own reviewable commit.
