# Product Center Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver usable internal SPU/SKU master data, product image archiving, Excel import/export, and product management pages without changing the existing order flow.

**Architecture:** Extend `erp-masterdata` with product catalog, import/export, and image metadata capabilities while keeping storage behind a port. Expose secured APIs from `erp-boot`; the Vue admin consumes those APIs through a focused product client. Long-running workbook operations create persisted jobs and run outside the request thread.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Spring JDBC, Flyway, MySQL 8.4, Apache POI 5.5.1, MinIO Java SDK 8.6.0, Vue 3.5.40, Vue Router 5.2.0, Vitest 4.1.10, Testing Library, MSW 2.15.0, Playwright 1.62.0.

## Global Constraints

- Internal SKU codes are enterprise-defined, globally unique, and immutable after creation.
- Every SKU belongs to exactly one SPU; imports without an SPU create a deterministic default SPU.
- Product states are exactly `DRAFT`, `ACTIVE`, `DISABLED`, and `ARCHIVED`.
- Only `ACTIVE` SKUs may be selected by new downstream business documents; historical references remain readable.
- Nonblank barcodes are globally unique.
- Referenced products are never physically deleted.
- Imports use upload, preflight, confirm, execute, and result stages; preflight never writes product data.
- Ten-thousand-row workbooks execute asynchronously and never hold an HTTP request open.
- Product images are stored in ERP-owned object storage; database rows retain the object key, source URL, checksum, and ordering.
- Important state, archive, import, and image changes write audit records with actor, reason, before value, and after value.
- Existing `MasterDataService.createSku(...)` and order ingestion behavior remain backward compatible.
- Phase 1 does not pull platform products, create channel-product records, or implement SKU mapping suggestions; those are Phase 2 and Phase 3.

---

## Target File Structure

### Backend domain and persistence

- `backend/erp-masterdata/src/main/resources/db/migration/V008__product_catalog.sql` — SPU, expanded SKU, image, import/export job, import error, and audit tables.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductStatus.java` — four-state lifecycle enum.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductCommands.java` — immutable nested command records, including `AuditActor`.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductViews.java` — nested list/detail/page response and filter records.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductErrors.java` — nested conflict, stale-version, and not-found exceptions.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductCatalogService.java` — write-side lifecycle and image metadata operations.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductQueryService.java` — paged list and detail reads.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/JdbcProductCatalogService.java` — transactional JDBC implementation and auditing.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/JdbcProductQueryService.java` — JDBC projections.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductObjectStore.java` — object-storage port for images and import/export workbooks.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/importing/ProductWorkbookService.java` — template, parse, validate, and export workbook logic.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/importing/ProductImportService.java` — persisted preflight/confirm/job execution.
- `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/importing/ProductJobViews.java` — job and row-error records.

### Backend delivery and infrastructure

- `backend/erp-boot/src/main/java/com/company/erp/api/ProductController.java` — SPU/SKU list, detail, create, edit, state, and image endpoints.
- `backend/erp-boot/src/main/java/com/company/erp/api/ProductImportController.java` — template, preflight, confirmation, status, errors, and export endpoints.
- `backend/erp-boot/src/main/java/com/company/erp/storage/MinioProductObjectStore.java` — MinIO adapter.
- `backend/erp-boot/src/main/java/com/company/erp/storage/ProductStorageProperties.java` — endpoint, credentials, bucket, and public URL configuration.
- `backend/erp-boot/src/main/java/com/company/erp/ProductCenterConfiguration.java` — beans and bounded executor.

### Frontend

- `frontend/apps/admin/src/api/products.ts` — product types and HTTP client.
- `frontend/apps/admin/src/views/products/ProductListView.vue` — filters, page table, state actions, and navigation.
- `frontend/apps/admin/src/views/products/ProductEditorView.vue` — create/edit SPU with nested SKUs and images.
- `frontend/apps/admin/src/views/products/ProductDetailView.vue` — read-only summary and audit history.
- `frontend/apps/admin/src/views/products/ProductImportView.vue` — five-stage import and export workflow.
- `frontend/apps/admin/src/views/products/productForm.ts` — form model, API conversion, and client validation.
- `frontend/apps/admin/e2e/product-flow.spec.ts` — browser acceptance flow.

---

### Task 1: Add Product Catalog Schema and Lifecycle Types

**Files:**
- Create: `backend/erp-masterdata/src/main/resources/db/migration/V008__product_catalog.sql`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductStatus.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductCommands.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductViews.java`
- Create: `backend/erp-masterdata/src/test/java/com/company/erp/masterdata/product/ProductCatalogMigrationIT.java`

**Interfaces:**
- Produces: `ProductStatus`, `CreateSpu`, `UpdateSpu`, `CreateSku`, `UpdateSku`, `ProductPage`, `ProductSummary`, and `ProductDetail`.
- Preserves: existing `md_sku.sku_code`, `barcode`, `batch_enabled`, `serial_enabled`, and foreign keys.

- [ ] **Step 1: Write the failing migration integration test**

```java
@Test
void migratesExistingSkuIntoAProductCatalogWithoutLosingItsCode() {
  jdbc.update("""
      insert into md_sku
        (id, sku_code, barcode, batch_enabled, serial_enabled, status)
      values ('01J00000000000000000000001', 'LEGACY-1', '690000000001', false, false, 'ACTIVE')
      """);
  migrateToLatest();

  assertThat(jdbc.queryForObject(
      "select count(*) from md_spu where spu_code = 'LEGACY-01J00000000000000000000001'",
      Integer.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject(
      "select spu_id from md_sku where sku_code = 'LEGACY-1'", String.class)).isNotBlank();
}
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run: `mvn -f backend/pom.xml -pl erp-masterdata -am -Dtest=ProductCatalogMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `V008__product_catalog.sql` and `md_spu` do not exist.

- [ ] **Step 3: Add the additive migration**

The migration must:

```sql
create table md_brand (
  id char(26) not null primary key,
  name varchar(128) not null,
  status varchar(16) not null default 'ACTIVE',
  created_at timestamp(6) not null default current_timestamp(6),
  constraint uk_md_brand_name unique (name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_category (
  id char(26) not null primary key,
  parent_id char(26) null,
  name varchar(128) not null,
  path varchar(1024) not null,
  status varchar(16) not null default 'ACTIVE',
  created_at timestamp(6) not null default current_timestamp(6),
  constraint uk_md_category_parent_name unique (parent_id, name),
  constraint fk_md_category_parent foreign key (parent_id) references md_category(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_spu (
  id char(26) not null primary key,
  spu_code varchar(64) not null,
  name varchar(256) not null,
  brand_id char(26) null,
  category_id char(26) null,
  attributes json null,
  status varchar(16) not null default 'DRAFT',
  version bigint not null default 0,
  created_at timestamp(6) not null default current_timestamp(6),
  updated_at timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
  constraint uk_md_spu_code unique (spu_code),
  constraint fk_md_spu_brand foreign key (brand_id) references md_brand(id),
  constraint fk_md_spu_category foreign key (category_id) references md_category(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
```

Migrate legacy SKUs deterministically using the existing ULID as the SPU ID:

```sql
alter table md_sku
  add column spu_id char(26) null after id,
  add column name varchar(256) null after sku_code,
  add column specifications json null after barcode,
  add column unit varchar(16) not null default '件' after specifications,
  add column version bigint not null default 0 after status;

insert into md_spu (id, spu_code, name, status)
select id, concat('LEGACY-', id), sku_code, status from md_sku;

update md_sku set spu_id = id, name = sku_code where spu_id is null;

alter table md_sku
  modify column spu_id char(26) not null,
  modify column name varchar(256) not null,
  add constraint fk_md_sku_spu foreign key (spu_id) references md_spu(id);
```

Create the supporting tables with these required columns and keys:

```sql
create table md_product_image (
  id char(26) not null primary key,
  spu_id char(26) not null,
  object_key varchar(512) not null,
  source_url varchar(1024) null,
  content_sha256 char(64) not null,
  media_type varchar(64) not null,
  display_order int not null default 0,
  created_at timestamp(6) not null default current_timestamp(6),
  constraint uk_md_product_image_object unique (object_key),
  constraint fk_md_product_image_spu foreign key (spu_id) references md_spu(id),
  index ix_md_product_image_spu_order (spu_id, display_order)
);

create table md_import_job (
  id char(26) not null primary key,
  filename varchar(256) not null,
  object_key varchar(512) not null,
  error_object_key varchar(512) null,
  status varchar(32) not null,
  create_count int not null default 0,
  update_count int not null default 0,
  skip_count int not null default 0,
  conflict_count int not null default 0,
  failure_count int not null default 0,
  confirmation_key varchar(128) null,
  created_by varchar(128) not null,
  created_at timestamp(6) not null default current_timestamp(6),
  started_at timestamp(6) null,
  finished_at timestamp(6) null,
  constraint uk_md_import_confirmation unique (confirmation_key),
  index ix_md_import_status_created (status, created_at)
);

create table md_import_error (
  id char(26) not null primary key,
  import_job_id char(26) not null,
  source_row_number int not null,
  error_code varchar(64) not null,
  message varchar(512) not null,
  constraint fk_md_import_error_job foreign key (import_job_id) references md_import_job(id),
  index ix_md_import_error_job_row (import_job_id, source_row_number)
);

create table md_export_job (
  id char(26) not null primary key,
  filter_json json not null,
  object_key varchar(512) null,
  status varchar(32) not null,
  idempotency_key varchar(128) not null,
  created_by varchar(128) not null,
  created_at timestamp(6) not null default current_timestamp(6),
  finished_at timestamp(6) null,
  constraint uk_md_export_idempotency unique (idempotency_key),
  index ix_md_export_status_created (status, created_at)
);

create table audit_log (
  id char(26) not null primary key,
  aggregate_type varchar(64) not null,
  aggregate_id char(26) not null,
  action varchar(64) not null,
  actor varchar(128) not null,
  reason varchar(512) not null,
  before_json json null,
  after_json json null,
  created_at timestamp(6) not null default current_timestamp(6),
  index ix_audit_aggregate_time (aggregate_type, aggregate_id, created_at)
);
```

- [ ] **Step 4: Add exact domain records**

```java
public enum ProductStatus {
  DRAFT, ACTIVE, DISABLED, ARCHIVED
}

public record CreateSpu(
    String spuCode, String name, String brandId, String categoryId,
    Map<String, String> attributes, List<CreateSku> skus) {}

public record CreateSku(
    String skuCode, String name, String barcode,
    Map<String, String> specifications, String unit) {}
```

Use `long version` on update commands and detail views for optimistic locking.

- [ ] **Step 5: Run the master-data tests**

Run: `mvn -f backend/pom.xml -pl erp-masterdata -am test`

Expected: PASS, including the legacy `MasterDataServiceIT`.

- [x] **Step 6: Commit**

```bash
git add backend/erp-masterdata
git commit -m "feat: add product catalog schema"
```

### Task 2: Implement Product Commands, Queries, Lifecycle, and Audit

**Files:**
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductCatalogService.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/JdbcProductCatalogService.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductQueryService.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/JdbcProductQueryService.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductErrors.java`
- Create: `backend/erp-masterdata/src/test/java/com/company/erp/masterdata/product/ProductCatalogServiceIT.java`
- Modify: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/JdbcMasterDataService.java`

**Interfaces:**
- Produces:

```java
String createSpu(CreateSpu command, AuditActor actor);
void updateSpu(String id, UpdateSpu command, AuditActor actor);
void changeStatus(String id, ProductStatus target, long version, String reason, AuditActor actor);
Optional<ProductDetail> get(String id);
ProductPage list(ProductFilter filter, int page, int size);
```

- `AuditActor` is `record AuditActor(String subject, Set<String> roles)`.
- `ProductFilter` contains nullable `keyword`, `barcode`, `brandId`, `categoryId`, and `ProductStatus status`.

- [ ] **Step 1: Write failing lifecycle and uniqueness tests**

Cover:

```java
assertThat(service.createSpu(command("SPU-1", "SKU-1", "6901"), actor())).isNotBlank();
assertThatThrownBy(() -> service.createSpu(command("SPU-2", "SKU-1", "6902"), actor()))
    .isInstanceOf(ProductConflictException.class)
    .hasMessageContaining("SKU-1");
service.changeStatus(spuId, ProductStatus.ACTIVE, 0, "审核通过", actor());
assertThat(queries.get(spuId)).get().extracting(ProductDetail::status)
    .isEqualTo(ProductStatus.ACTIVE);
```

Also assert that `ARCHIVED -> ACTIVE` is rejected, blank reasons are rejected for state changes, stale versions fail, and each important operation creates one `audit_log` row.

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `mvn -f backend/pom.xml -pl erp-masterdata -am -Dtest=ProductCatalogServiceIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because catalog services do not exist.

- [ ] **Step 3: Implement minimal transactional write service**

Use `TransactionTemplate`; insert the SPU and all SKUs atomically. Translate MySQL duplicate-key failures into:

```java
public final class ProductConflictException extends IllegalStateException {
  public ProductConflictException(String code, String value) {
    super(code + ": " + value);
  }
}
```

Allowed transitions:

```java
private static final Map<ProductStatus, Set<ProductStatus>> TRANSITIONS = Map.of(
    DRAFT, Set.of(ACTIVE, ARCHIVED),
    ACTIVE, Set.of(DISABLED, ARCHIVED),
    DISABLED, Set.of(ACTIVE, ARCHIVED),
    ARCHIVED, Set.of());
```

All updates use `where id = ? and version = ?` and increment `version`.

- [ ] **Step 4: Implement paged queries**

Return stable pages ordered by `updated_at desc, id desc`; use bound parameters only. `keyword` searches exact SKU/SPU codes first and escaped `LIKE` against names second. Page size is restricted to `1..100`.

- [ ] **Step 5: Preserve legacy SKU creation**

Change `JdbcMasterDataService.createSku(...)` to create a deterministic single-SKU SPU and the SKU in one transaction while preserving the current method signature and returned SKU ID.

- [ ] **Step 6: Run module tests**

Run: `mvn -f backend/pom.xml -pl erp-masterdata -am test`

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add backend/erp-masterdata
git commit -m "feat: implement product catalog lifecycle"
```

### Task 3: Expose Secured Product APIs

**Files:**
- Create: `backend/erp-boot/src/main/java/com/company/erp/api/ProductController.java`
- Create: `backend/erp-boot/src/test/java/com/company/erp/api/ProductControllerIT.java`
- Create: `backend/erp-boot/src/main/java/com/company/erp/ProductCenterConfiguration.java`
- Modify: `backend/erp-boot/src/main/java/com/company/erp/api/ApiExceptionHandler.java`
- Modify: `backend/erp-identity/src/main/java/com/company/erp/identity/SecurityConfiguration.java`
- Modify: `backend/erp-boot/src/main/java/com/company/erp/ErpApplication.java`

**Interfaces:**
- Consumes: Task 2 catalog and query signatures.
- Produces:
  - `GET /api/products/spus`
  - `POST /api/products/spus`
  - `GET /api/products/spus/{id}`
  - `PUT /api/products/spus/{id}`
  - `POST /api/products/spus/{id}/status`
  - `GET /api/products/brands`
  - `POST /api/products/brands`
  - `GET /api/products/categories`
  - `POST /api/products/categories`

- [ ] **Step 1: Write failing MockMvc authorization and contract tests**

```java
mvc.perform(get("/api/products/spus").with(productJwt("ROLE_PRODUCT_VIEW")))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.items[0].spuCode").value("SPU-1"));

mvc.perform(post("/api/products/spus").with(productJwt("ROLE_PRODUCT_VIEW"))
        .contentType(APPLICATION_JSON).content(validCreateBody()))
    .andExpect(status().isForbidden());

mvc.perform(post("/api/products/spus").with(productJwt("ROLE_PRODUCT_ADMIN"))
        .contentType(APPLICATION_JSON).content(validCreateBody()))
    .andExpect(status().isCreated());
```

Also test that `PRODUCT_OPERATOR` can edit ordinary product fields but cannot create, change lifecycle state, archive, or manage lookups. Test unauthenticated 401, stale version 409, duplicate SKU 409, missing reason 400, and missing product 404.

- [ ] **Step 2: Run the controller test and verify it fails**

Run: `mvn -f backend/pom.xml -pl erp-boot -am -Dtest=ProductControllerIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the controller and roles are absent.

- [ ] **Step 3: Add endpoint security**

```java
.requestMatchers("/api/products/**")
    .hasAnyRole("PRODUCT_VIEW", "PRODUCT_OPERATOR", "PRODUCT_ADMIN")
```

Enable method security. Apply `PRODUCT_ADMIN` to create, lifecycle, archive, brand, and category writes; apply `PRODUCT_OPERATOR` or `PRODUCT_ADMIN` to ordinary SPU/SKU edits; apply all three product roles to reads. Keep the existing order rules unchanged and place product matchers before `.anyRequest().authenticated()`.

- [ ] **Step 4: Implement request validation and responses**

Use explicit request records with Jakarta validation. Status requests are:

```java
record ChangeProductStatusRequest(ProductStatus status, long version, String reason) {}
```

Derive `AuditActor.subject` and roles from `JwtAuthenticationToken`; never accept actor identity from the request body.

- [ ] **Step 5: Map domain errors**

Return:

- `PRODUCT_CONFLICT` with HTTP 409 for duplicate codes/barcodes.
- `STALE_PRODUCT_VERSION` with HTTP 409 for optimistic-lock failures.
- `PRODUCT_NOT_FOUND` with HTTP 404.
- Existing `INVALID_REQUEST` with HTTP 400 for invalid states and fields.

- [ ] **Step 6: Run API and module tests**

Run: `mvn -f backend/pom.xml -pl erp-boot -am test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/erp-boot backend/erp-identity
git commit -m "feat: expose secured product catalog APIs"
```

### Task 4: Add Excel Template and Preflight Validation

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/erp-masterdata/pom.xml`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/importing/ProductWorkbookService.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/importing/ProductJobViews.java`
- Create: `backend/erp-masterdata/src/test/java/com/company/erp/masterdata/importing/ProductWorkbookServiceTest.java`

**Interfaces:**
- Produces:

```java
byte[] createTemplate();
ProductPreflight parseAndValidate(InputStream workbook, ExistingProductIndex existing);
byte[] createErrorWorkbook(ProductPreflight preflight);
byte[] exportProducts(Stream<ProductExportRow> products);
```

- `ProductPreflight` contains `List<ValidProductRow> validRows`, `List<ProductRowError> errors`, and counts for create, update, skip, conflict, and failure.

- [ ] **Step 1: Lock Apache POI 5.5.1 and write failing workbook tests**

Add root property `poi.version=5.5.1` and `org.apache.poi:poi-ooxml`.

The template test must assert exact columns:

```text
SPU编码, SPU名称, 品牌, 类目, SKU编码, SKU名称, 条码, 规格JSON, 计量单位, 商品状态
```

Validation tests must cover missing required fields, invalid JSON, duplicate SKU in the workbook, duplicate barcode in the database, invalid status, update-vs-create classification, and row number preservation.

Brand cells contain the canonical brand name. Category cells contain a slash-delimited path such as `数码/配件`. Preflight reports conflicting case variants, and confirmed import creates missing brand or category nodes before inserting the SPU; blank brand and category remain allowed.

- [ ] **Step 2: Run the workbook test and verify it fails**

Run: `mvn -f backend/pom.xml -pl erp-masterdata -am -Dtest=ProductWorkbookServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because POI and the workbook service are absent.

- [ ] **Step 3: Implement template and bounded parsing**

Reject files over 20 MiB and workbooks over 10,000 data rows. Normalize whitespace, preserve SKU/barcode strings as text, and reject formulas in business columns. Group rows by SPU code after all row-level validation succeeds.

- [ ] **Step 4: Implement deterministic error output**

Append `错误代码` and `错误说明` columns. Use stable codes:

```text
REQUIRED_FIELD, INVALID_JSON, INVALID_STATUS, DUPLICATE_SKU,
DUPLICATE_BARCODE, CONFLICTING_SPU, ROW_LIMIT_EXCEEDED
```

- [ ] **Step 5: Run tests**

Run: `mvn -f backend/pom.xml -pl erp-masterdata -am test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/erp-masterdata
git commit -m "feat: validate product workbooks"
```

### Task 5: Persist and Execute Import/Export Jobs

**Files:**
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/importing/ProductImportService.java`
- Create: `backend/erp-masterdata/src/test/java/com/company/erp/masterdata/importing/ProductImportServiceIT.java`
- Create: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductObjectStore.java`
- Create: `backend/erp-boot/src/main/java/com/company/erp/api/ProductImportController.java`
- Create: `backend/erp-boot/src/test/java/com/company/erp/api/ProductImportControllerIT.java`
- Create: `backend/erp-boot/src/main/java/com/company/erp/storage/ProductStorageProperties.java`
- Create: `backend/erp-boot/src/main/java/com/company/erp/storage/MinioProductObjectStore.java`
- Create: `backend/erp-boot/src/test/java/com/company/erp/storage/MinioProductObjectStoreIT.java`
- Modify: `backend/erp-boot/src/main/java/com/company/erp/ProductCenterConfiguration.java`
- Modify: `backend/erp-identity/src/main/java/com/company/erp/identity/SecurityConfiguration.java`
- Modify: `backend/pom.xml`
- Modify: `backend/erp-boot/pom.xml`
- Modify: `backend/erp-boot/src/main/resources/application.yml`

**Interfaces:**
- Produces:

```java
String createPreflightJob(InputStream workbook, long size, String filename, AuditActor actor);
void executePreflight(String jobId);
void confirm(String jobId, String idempotencyKey, AuditActor actor);
ImportJobView getImportJob(String jobId);
void executeConfirmedJob(String jobId);
String createExport(ProductFilter filter, String idempotencyKey, AuditActor actor);
ExportJobView getExportJob(String jobId);
void executeExportJob(String jobId);
```

- [x] **Step 1: Lock MinIO SDK 8.6.0 and write failing object-storage integration tests**

Add root property `minio.version=8.6.0` and `io.minio:minio` to `erp-boot`. Start the existing MinIO image with Testcontainers, upload workbook bytes, read them back, and assert checksum equality and bucket creation.

- [x] **Step 2: Run the storage test and verify it fails**

Run: `mvn -f backend/pom.xml -pl erp-boot -am -Dtest=MinioProductObjectStoreIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the storage adapter does not exist.

- [x] **Step 3: Implement the general product object store**

```java
StoredProductObject put(String objectKey, InputStream body, long size, String contentType, String sha256);
InputStream get(String objectKey);
void deleteUnreferenced(String objectKey);
```

Configure endpoint, credentials, and bucket through `erp.storage`; never expose credentials through actuator or API responses.

```yaml
erp:
  storage:
    endpoint: ${ERP_STORAGE_ENDPOINT:http://localhost:9000}
    access-key: ${ERP_STORAGE_ACCESS_KEY:erp_local}
    secret-key: ${ERP_STORAGE_SECRET_KEY:erp_local_secret}
    product-bucket: ${ERP_PRODUCT_BUCKET:erp-products}
```

- [x] **Step 4: Write failing job-state integration tests**

Assert the exact import state machine:

```text
UPLOADED -> PREFLIGHT_READY -> CONFIRMED -> RUNNING -> SUCCEEDED
                                            \-> PARTIALLY_SUCCEEDED
                                            \-> FAILED
```

Assert upload returns a job ID before parsing begins, `executePreflight` writes no SPU/SKU rows, duplicate confirmation with the same idempotency key creates one execution, legal rows commit in bounded groups of 200, and row failures remain downloadable.

- [x] **Step 5: Run the import test and verify it fails**

Run: `mvn -f backend/pom.xml -pl erp-masterdata -am -Dtest=ProductImportServiceIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the service does not exist.

- [x] **Step 6: Implement persisted job transitions**

Use conditional updates such as:

```sql
update md_import_job
set status = 'RUNNING', started_at = current_timestamp(6)
where id = ? and status = 'CONFIRMED'
```

If the update count is zero, another worker already owns the job. Store the uploaded workbook under a generated object key; never store workbook bytes in MySQL.

- [x] **Step 7: Add a bounded executor**

Configure a `ThreadPoolTaskExecutor` with core size 2, max size 4, queue capacity 100, and thread prefix `product-job-`. Upload stores the workbook and submits only the persisted job ID for preflight; confirmation submits only the same persisted job ID for execution.

- [x] **Step 8: Add secured job endpoints**

Use `ROLE_PRODUCT_ADMIN` for upload/confirm and `ROLE_PRODUCT_VIEW`, `ROLE_PRODUCT_OPERATOR`, or `ROLE_PRODUCT_ADMIN` for job/result reads. Require `Idempotency-Key` on confirmation and export creation.

- [x] **Step 9: Run backend tests**

Run: `mvn -f backend/pom.xml -pl erp-boot -am test`

Expected: PASS.

- [x] **Step 10: Commit**

```bash
git add backend/pom.xml backend/erp-masterdata backend/erp-boot backend/erp-identity
git commit -m "feat: run product import and export jobs"
```

### Task 6: Archive Product Images Through the Product Object Store

**Files:**
- Modify: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/ProductCatalogService.java`
- Modify: `backend/erp-masterdata/src/main/java/com/company/erp/masterdata/product/JdbcProductCatalogService.java`
- Create: `backend/erp-masterdata/src/test/java/com/company/erp/masterdata/product/ProductImageServiceIT.java`
- Modify: `backend/erp-boot/src/main/java/com/company/erp/api/ProductController.java`
- Modify: `backend/erp-boot/src/test/java/com/company/erp/api/ProductControllerIT.java`

**Interfaces:**
- Consumes: Task 5 `ProductObjectStore`.
- Produces: image metadata write/reorder/remove methods on `ProductCatalogService`.

- `POST /api/products/spus/{id}/images` accepts one JPEG, PNG, or WebP image up to 10 MiB.

- [x] **Step 1: Write failing image metadata and controller tests**

Upload `image/png` bytes through the controller, assert checksum-based object naming, image ordering, audit creation, and metadata removal without deleting a still-referenced object.

- [x] **Step 2: Run the storage test and verify it fails**

Run: `mvn -f backend/pom.xml -pl erp-boot -am -Dtest=ProductImageServiceIT,ProductControllerIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because image catalog operations and the upload endpoint do not exist.

- [x] **Step 3: Add image metadata operations**

Add `addImage`, `reorderImages`, and `removeImage` to `ProductCatalogService`. Each method requires the current SPU version, an audit actor, and a nonblank reason for removal.

- [x] **Step 4: Implement validated upload**

Read the first bytes to validate the actual image format instead of trusting the filename. Object keys use `products/{spuId}/{sha256}.{extension}` so duplicate uploads are idempotent. Insert `md_product_image` only after object storage succeeds.

- [x] **Step 5: Test controller authorization and audit**

`ROLE_PRODUCT_ADMIN` can upload/reorder/remove; viewers can read metadata. Removing an image deletes metadata immediately and schedules physical deletion only when no row references the object key.

- [x] **Step 6: Run backend tests**

Run: `mvn -f backend/pom.xml -pl erp-boot -am test`

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add backend/pom.xml backend/erp-masterdata backend/erp-boot
git commit -m "feat: archive product images in object storage"
```

### Task 7: Add Product Frontend Client, Routes, and Navigation

**Files:**
- Create: `frontend/apps/admin/src/api/products.ts`
- Create: `frontend/apps/admin/src/api/products.spec.ts`
- Modify: `frontend/apps/admin/src/router.ts`
- Modify: `frontend/apps/admin/src/App.vue`

**Interfaces:**
- Produces:

```ts
export type ProductStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
export type ProductSummary = {
  id: string; spuCode: string; name: string; brandName: string | null
  categoryName: string | null; status: ProductStatus; skuCount: number
  channelMappingCount: number; mainImageUrl: string | null
  updatedAt: string; version: number
}
export function listProducts(filter: ProductFilter): Promise<ProductPage>
export function getProduct(id: string): Promise<ProductDetail>
export function createProduct(input: ProductInput): Promise<{ id: string }>
export function updateProduct(id: string, input: ProductUpdate): Promise<void>
export function changeProductStatus(id: string, input: StatusChange): Promise<void>
```

- [x] **Step 1: Write failing API client tests with MSW**

Assert query encoding, bearer token forwarding, 409 error conversion, and `Idempotency-Key` forwarding for job endpoints.

- [x] **Step 2: Run the client test and verify it fails**

Run: `pnpm --dir frontend --filter admin test --run src/api/products.spec.ts`

Expected: FAIL because `products.ts` does not exist.

- [x] **Step 3: Implement the typed client**

Use the existing `tokenStore`; convert non-2xx responses into:

```ts
export class ProductApiError extends Error {
  constructor(public code: string, message: string, public status: number) {
    super(message)
  }
}
```

- [x] **Step 4: Add routes and active navigation**

Add:

```ts
{ path: '/products', name: 'products', component: ProductListView },
{ path: '/products/new', name: 'product-new', component: ProductEditorView },
{ path: '/products/:id', name: 'product-detail', component: ProductDetailView },
{ path: '/products/:id/edit', name: 'product-edit', component: ProductEditorView },
{ path: '/products/imports', name: 'product-imports', component: ProductImportView },
```

Convert the existing 商品 placeholder into a `RouterLink` and derive breadcrumbs from route metadata rather than hard-coding order labels.

- [x] **Step 5: Run frontend tests and typecheck**

Run: `pnpm --dir frontend test`

Run: `pnpm --dir frontend typecheck`

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add frontend/apps/admin/src/api frontend/apps/admin/src/router.ts frontend/apps/admin/src/App.vue
git commit -m "feat: add product routes and API client"
```

### Task 8: Build the Product List and Lifecycle Actions

**Files:**
- Create: `frontend/apps/admin/src/views/products/ProductListView.vue`
- Create: `frontend/apps/admin/src/views/products/ProductListView.spec.ts`
- Modify: `frontend/apps/admin/src/styles.css`

**Interfaces:**
- Consumes: Task 7 `listProducts` and `changeProductStatus`.
- Produces: accessible product list at `/products`.

- [x] **Step 1: Write failing component tests**

Test:

- initial page load;
- keyword, barcode, brand, category, and status filters;
- page navigation;
- empty, loading, and error states;
- disable/archive confirmation requiring a nonblank reason;
- stale-version 409 refresh prompt;
- links to create, detail, edit, and import.

```ts
await user.type(screen.getByLabelText('SKU/商品名称'), 'SKU-1')
await user.click(screen.getByRole('button', { name: '查询' }))
expect(requestedUrl.searchParams.get('keyword')).toBe('SKU-1')
```

- [x] **Step 2: Run the component test and verify it fails**

Run: `pnpm --dir frontend --filter admin test --run src/views/products/ProductListView.spec.ts`

Expected: FAIL because the view does not exist.

- [x] **Step 3: Implement the accessible list**

Use semantic table markup, explicit labels, real buttons, and status text in addition to color. Keep filters in route query parameters so refresh and back navigation preserve state.

- [x] **Step 4: Add responsive layout**

At widths below 768 px, stack filters and render each row as a labelled product card without hiding SKU code, status, or primary action.

- [x] **Step 5: Run focused and full frontend tests**

Run: `pnpm --dir frontend --filter admin test --run src/views/products/ProductListView.spec.ts`

Run: `pnpm --dir frontend test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/apps/admin/src/views/products frontend/apps/admin/src/styles.css
git commit -m "feat: build product catalog list"
```

### Task 9: Build Product Create, Edit, Detail, and Image Flows

**Files:**
- Create: `frontend/apps/admin/src/views/products/productForm.ts`
- Create: `frontend/apps/admin/src/views/products/ProductEditorView.vue`
- Create: `frontend/apps/admin/src/views/products/ProductEditorView.spec.ts`
- Create: `frontend/apps/admin/src/views/products/ProductDetailView.vue`
- Create: `frontend/apps/admin/src/views/products/ProductDetailView.spec.ts`
- Modify: `frontend/apps/admin/src/api/products.ts`
- Modify: `frontend/apps/admin/src/styles.css`

**Interfaces:**
- Consumes: Task 7 product APIs and Task 6 image endpoint.
- Produces: create/edit/detail pages with nested SKU rows.

- [x] **Step 1: Write failing form-model unit tests**

```ts
expect(validateProductForm({
  spuCode: 'SPU-1',
  name: '智能水杯',
  skus: [{ skuCode: 'SKU-1', name: '黑色', barcode: '6901', unit: '件', specifications: {} }],
})).toEqual({})
```

Cover duplicate SKU rows, duplicate nonblank barcodes, missing SPU/name/SKU/unit, malformed attributes, and immutable SKU code behavior during edit.

- [x] **Step 2: Write failing editor and detail component tests**

Test add/remove SKU row, submit create, optimistic update, 409 display, image type/size rejection, image upload progress, and audit-history rendering.

- [x] **Step 3: Run the tests and verify they fail**

Run: `pnpm --dir frontend --filter admin test --run src/views/products`

Expected: FAIL because editor and detail views are absent.

- [x] **Step 4: Implement create/edit form**

Use stable row IDs independent of SKU code. Prevent removing the last SKU. Disable SKU code editing for persisted rows. Show server conflict messages next to the matching SKU or barcode field. Brand and category selectors load lookup APIs and allow a product administrator to create a missing brand or category without leaving the form.

Core create/edit, SKU validation, immutable persisted codes, field-level server conflicts, and searchable brand/category lookup with inline creation are complete.

- [x] **Step 5: Implement detail and image management**

Display SPU metadata, all SKUs, image gallery, lifecycle, timestamps, version, and audit history. Image upload accepts only JPEG/PNG/WebP and 10 MiB before sending.

- [x] **Step 6: Run frontend verification**

Run: `pnpm --dir frontend test`

Run: `pnpm --dir frontend typecheck`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/apps/admin/src
git commit -m "feat: add product editor and detail"
```

### Task 10: Build the Five-Stage Import and Export UI

**Files:**
- Create: `frontend/apps/admin/src/views/products/ProductImportView.vue`
- Create: `frontend/apps/admin/src/views/products/ProductImportView.spec.ts`
- Modify: `frontend/apps/admin/src/api/products.ts`
- Modify: `frontend/apps/admin/src/styles.css`

**Interfaces:**
- Consumes: Task 5 import/export endpoints.
- Produces: template download, upload, preflight, confirmation, progress, results, error download, and export controls.

- [x] **Step 1: Write failing workflow component tests**

Assert:

```text
选择文件 -> 上传预检 -> 查看统计与错误 -> 确认导入 -> 查看任务进度 -> 下载结果
```

Test that preflight never labels data as imported, confirmation uses a generated idempotency key once, repeated clicks are disabled, partial success exposes the error workbook, and page reload resumes by job ID in the URL.

- [x] **Step 2: Run the focused test and verify it fails**

Run: `pnpm --dir frontend --filter admin test --run src/views/products/ProductImportView.spec.ts`

Expected: FAIL because the view does not exist.

- [x] **Step 3: Implement the workflow**

Poll only while status is `CONFIRMED` or `RUNNING`, using 2-second intervals with a 30-second maximum before switching to a manual refresh button. Clear timers on unmount.

- [x] **Step 4: Implement export**

Create an export job from current product filters, poll it using the same bounded policy, and download only after the API reports `SUCCEEDED`.

- [x] **Step 5: Run frontend tests**

Run: `pnpm --dir frontend test`

Run: `pnpm --dir frontend typecheck`

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add frontend/apps/admin/src
git commit -m "feat: add product import and export center"
```

### Task 11: Add End-to-End Acceptance, Operations Notes, and Final Gate

**Files:**
- Create: `frontend/apps/admin/e2e/product-flow.spec.ts`
- Create: `docs/runbooks/product-center-phase-1.md`

**Interfaces:**
- Verifies all Phase 1 deliverables without depending on platform connectors.

- [x] **Step 1: Write the failing browser flow**

The Playwright flow must:

1. open 商品;
2. create one SPU with two SKUs;
3. find it through SKU search;
4. open detail;
5. disable it with a reason;
6. open import center;
7. upload a fixture workbook;
8. verify preflight counts before confirmation;
9. confirm and wait for success;
10. verify imported SKU appears in product search.

- [x] **Step 2: Run the browser test and verify the first incomplete behavior fails**

Run: `pnpm --dir frontend --filter admin e2e -- product-flow.spec.ts`

Expected: FAIL at the first Phase 1 behavior not yet correctly wired to the test environment.

- [x] **Step 3: Add deterministic E2E setup**

Use API-level setup and cleanup limited to product test records whose codes begin `E2E-`. Never delete non-test product rows. Configure the test JWT with `PRODUCT_VIEW` and `PRODUCT_ADMIN`.

- [x] **Step 4: Write the operations runbook**

Document:

- required MySQL and MinIO environment variables;
- product bucket creation;
- role claims;
- supported workbook columns and limits;
- import-job recovery query and safe retry procedure;
- object-store backup requirements;
- health verification and rollback rules for `V008`.

- [x] **Step 5: Run the complete verification gate**

Run: `mvn -f backend/pom.xml verify`

Run: `pnpm --dir frontend test`

Run: `pnpm --dir frontend typecheck`

Run: `pnpm --dir frontend build`

Run: `pnpm --dir frontend --filter admin e2e -- product-flow.spec.ts`

Expected: all commands PASS with no skipped Phase 1 acceptance test.

- [x] **Step 6: Inspect the rendered UI**

Verify desktop 1536×1024 and mobile 390×844 views for nonblank content, no framework error overlay, no horizontal page overflow, usable form controls, and no browser console errors.

- [x] **Step 7: Commit**

```bash
git add frontend/apps/admin/e2e docs/runbooks
git commit -m "test: verify product center phase one"
```

---

## Spec Coverage and Deferred Plans

This plan implements the approved design sections for internal SPU/SKU data, lifecycle, Excel import/export, images, permissions, audit, performance bounds, management APIs, pages, and Phase 1 acceptance.

Two independent plans intentionally follow after this plan passes:

1. **Phase 2 — Channel Product Synchronization:** unified connector contract, raw payload archive, sync jobs, image compensation, and read-only adapters for the eight approved platforms.
2. **Phase 3 — SKU Mapping and Order Recovery:** channel SKU pool, exact matching, suggestion workbench, conflicts, mapping audit, `ChannelSkuMapped`, and idempotent order-exception recovery.

Neither deferred plan may weaken the internal SKU identity, lifecycle, audit, or idempotency rules established here.
