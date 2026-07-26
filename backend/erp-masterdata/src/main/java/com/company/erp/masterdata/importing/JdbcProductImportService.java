package com.company.erp.masterdata.importing;

import com.company.erp.masterdata.importing.ProductJobViews.ImportJobView;
import com.company.erp.masterdata.importing.ProductWorkbookService.ExistingProductIndex;
import com.company.erp.masterdata.importing.ProductWorkbookService.ValidProductRow;
import com.company.erp.masterdata.product.ProductCatalogService;
import com.company.erp.masterdata.product.ProductCommands.AuditActor;
import com.company.erp.masterdata.product.ProductCommands.CreateSku;
import com.company.erp.masterdata.product.ProductCommands.CreateSpu;
import com.company.erp.masterdata.product.ProductCommands.UpdateSku;
import com.company.erp.masterdata.product.ProductCommands.UpdateSpu;
import com.company.erp.masterdata.product.ProductObjectStore;
import com.company.erp.masterdata.product.ProductQueryService;
import com.company.erp.shared.Ids;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcProductImportService implements ProductImportService {
  private static final String WORKBOOK_MEDIA_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final int BATCH_SIZE = 200;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ProductObjectStore objects;
  private final ProductWorkbookService workbooks;
  private final ProductCatalogService catalog;
  private final ProductQueryService queries;

  public JdbcProductImportService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      ProductObjectStore objects,
      ProductWorkbookService workbooks,
      ProductCatalogService catalog,
      ProductQueryService queries) {
    this.jdbc = require(jdbc, "jdbc");
    this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    this.objects = require(objects, "objects");
    this.workbooks = require(workbooks, "workbooks");
    this.catalog = require(catalog, "catalog");
    this.queries = require(queries, "queries");
  }

  @Override
  public String createPreflightJob(
      InputStream workbook,
      long size,
      String filename,
      AuditActor actor) {
    requireActor(actor);
    requireText(filename, "filename");
    if (size < 0 || size > ProductWorkbookService.MAX_WORKBOOK_BYTES) {
      throw new IllegalArgumentException("Workbook size is invalid");
    }
    var bytes = readExactly(workbook, size);
    var jobId = Ids.newId();
    var objectKey = "imports/" + jobId + "/source.xlsx";
    objects.put(
        objectKey,
        new ByteArrayInputStream(bytes),
        bytes.length,
        WORKBOOK_MEDIA_TYPE,
        sha256(bytes));
    try {
      jdbc.update(
          """
          insert into md_import_job
            (id, filename, object_key, status, created_by)
          values (?, ?, ?, 'UPLOADED', ?)
          """,
          jobId,
          filename.trim(),
          objectKey,
          actor.subject());
    } catch (RuntimeException exception) {
      objects.deleteUnreferenced(objectKey);
      throw exception;
    }
    return jobId;
  }

  @Override
  public void executePreflight(String jobId) {
    var sourceKey = jdbc.query(
        "select object_key from md_import_job where id = ? and status = 'UPLOADED'",
        (result, row) -> result.getString(1),
        jobId);
    if (sourceKey.isEmpty()) {
      return;
    }
    var preflight = workbooks.parseAndValidate(objects.get(sourceKey.getFirst()), existingIndex());
    var errorObjectKey = preflight.errors().isEmpty()
        ? null
        : storeErrorWorkbook(jobId, preflight);
    transactions.executeWithoutResult(status -> {
      jdbc.update("delete from md_import_error where import_job_id = ?", jobId);
      for (var error : preflight.errors()) {
        jdbc.update(
            """
            insert into md_import_error
              (id, import_job_id, source_row_number, error_code, message)
            values (?, ?, ?, ?, ?)
            """,
            Ids.newId(),
            jobId,
            error.sourceRowNumber(),
            error.code(),
            error.message());
      }
      jdbc.update(
          """
          update md_import_job
          set status = 'PREFLIGHT_READY',
              error_object_key = ?,
              create_count = ?,
              update_count = ?,
              skip_count = ?,
              conflict_count = ?,
              failure_count = ?
          where id = ? and status = 'UPLOADED'
          """,
          errorObjectKey,
          preflight.createCount(),
          preflight.updateCount(),
          preflight.skipCount(),
          preflight.conflictCount(),
          preflight.failureCount(),
          jobId);
    });
  }

  @Override
  public void confirm(String jobId, String idempotencyKey, AuditActor actor) {
    requireActor(actor);
    requireText(idempotencyKey, "idempotencyKey");
    var current = jdbc.query(
        "select status, confirmation_key from md_import_job where id = ?",
        (result, row) -> new String[] {result.getString(1), result.getString(2)},
        jobId);
    if (current.isEmpty()) {
      throw new IllegalArgumentException("Import job not found: " + jobId);
    }
    if ("CONFIRMED".equals(current.getFirst()[0])
        && idempotencyKey.equals(current.getFirst()[1])) {
      return;
    }
    try {
      var changed = jdbc.update(
          """
          update md_import_job
          set status = 'CONFIRMED', confirmation_key = ?
          where id = ? and status = 'PREFLIGHT_READY'
          """,
          idempotencyKey.trim(),
          jobId);
      if (changed == 0) {
        throw new IllegalStateException("Import job cannot be confirmed from its current state");
      }
    } catch (DuplicateKeyException exception) {
      throw new IllegalStateException("Idempotency key already belongs to another import", exception);
    }
  }

  @Override
  public ImportJobView getImportJob(String jobId) {
    var jobs = jdbc.query(
        """
        select id, filename, status, create_count, update_count, skip_count,
               conflict_count, failure_count, error_object_key,
               created_at, started_at, finished_at
        from md_import_job where id = ?
        """,
        (result, row) -> new ImportJobView(
            result.getString("id"),
            result.getString("filename"),
            result.getString("status"),
            result.getInt("create_count"),
            result.getInt("update_count"),
            result.getInt("skip_count"),
            result.getInt("conflict_count"),
            result.getInt("failure_count"),
            result.getString("error_object_key"),
            instant(result.getTimestamp("created_at")),
            instant(result.getTimestamp("started_at")),
            instant(result.getTimestamp("finished_at"))),
        jobId);
    if (jobs.isEmpty()) {
      throw new IllegalArgumentException("Import job not found: " + jobId);
    }
    return jobs.getFirst();
  }

  @Override
  public void executeConfirmedJob(String jobId) {
    var claimed = jdbc.update(
        """
        update md_import_job
        set status = 'RUNNING', started_at = current_timestamp(6)
        where id = ? and status = 'CONFIRMED'
        """,
        jobId);
    if (claimed == 0) {
      return;
    }
    try {
      var objectKey = jdbc.queryForObject(
          "select object_key from md_import_job where id = ?",
          String.class,
          jobId);
      var preflight = workbooks.parseAndValidate(objects.get(objectKey), existingIndex());
      var groups = preflight.validRows().stream().collect(Collectors.groupingBy(
          ValidProductRow::spuCode,
          LinkedHashMap::new,
          Collectors.toList()));
      var entries = new ArrayList<>(groups.entrySet());
      var actor = new AuditActor(
          jdbc.queryForObject(
              "select created_by from md_import_job where id = ?",
              String.class,
              jobId),
          Set.of("PRODUCT_ADMIN"));
      var executionFailures = 0;
      for (var offset = 0; offset < entries.size(); offset += BATCH_SIZE) {
        var batch = entries.subList(offset, Math.min(offset + BATCH_SIZE, entries.size()));
        executionFailures += transactions.execute(status -> executeBatch(jobId, batch, actor));
      }
      var totalFailures = preflight.failureCount() + executionFailures;
      jdbc.update(
          """
          update md_import_job
          set status = ?, failure_count = ?, finished_at = current_timestamp(6)
          where id = ? and status = 'RUNNING'
          """,
          totalFailures == 0 ? "SUCCEEDED" : "PARTIALLY_SUCCEEDED",
          totalFailures,
          jobId);
    } catch (RuntimeException exception) {
      jdbc.update(
          """
          update md_import_job
          set status = 'FAILED', finished_at = current_timestamp(6)
          where id = ? and status = 'RUNNING'
          """,
          jobId);
      throw exception;
    }
  }

  private int executeBatch(
      String jobId,
      List<Map.Entry<String, List<ValidProductRow>>> batch,
      AuditActor actor) {
    var failures = 0;
    for (var entry : batch) {
      try {
        upsertSpu(entry.getKey(), entry.getValue(), actor);
      } catch (RuntimeException exception) {
        failures++;
        var firstRow = entry.getValue().getFirst();
        jdbc.update(
            """
            insert into md_import_error
              (id, import_job_id, source_row_number, error_code, message)
            values (?, ?, ?, 'EXECUTION_FAILED', ?)
            """,
            Ids.newId(),
            jobId,
            firstRow.sourceRowNumber(),
            safeMessage(exception));
      }
    }
    return failures;
  }

  private void upsertSpu(String spuCode, List<ValidProductRow> rows, AuditActor actor) {
    var ids = jdbc.query(
        "select id from md_spu where spu_code = ?",
        (result, row) -> result.getString(1),
        spuCode);
    if (ids.isEmpty()) {
      var first = rows.getFirst();
      catalog.createSpu(
          new CreateSpu(
              spuCode,
              first.spuName(),
              null,
              null,
              Map.of(),
              rows.stream().map(JdbcProductImportService::createSku).toList()),
          actor);
      return;
    }
    var detail = queries.get(ids.getFirst()).orElseThrow();
    var imported = rows.stream().collect(Collectors.toMap(ValidProductRow::skuCode, row -> row));
    var updates = detail.skus().stream()
        .filter(sku -> imported.containsKey(sku.skuCode()))
        .map(sku -> {
          var row = imported.get(sku.skuCode());
          return new UpdateSku(
              sku.id(),
              row.skuName(),
              row.barcode(),
              row.specifications(),
              row.unit(),
              row.status(),
              sku.version());
        })
        .toList();
    var existingCodes = detail.skus().stream()
        .map(sku -> sku.skuCode())
        .collect(Collectors.toSet());
    var additions = rows.stream()
        .filter(row -> !existingCodes.contains(row.skuCode()))
        .map(JdbcProductImportService::createSku)
        .toList();
    catalog.updateSpu(
        detail.id(),
        new UpdateSpu(
            rows.getFirst().spuName(),
            detail.brandId(),
            detail.categoryId(),
            detail.attributes(),
            updates,
            additions,
            detail.version()),
        actor);
  }

  private ExistingProductIndex existingIndex() {
    var spuCodes = Set.copyOf(jdbc.queryForList("select spu_code from md_spu", String.class));
    var skuToSpu = jdbc.query(
        """
        select sku.sku_code, spu.spu_code
        from md_sku sku join md_spu spu on spu.id = sku.spu_id
        """,
        result -> {
          var values = new LinkedHashMap<String, String>();
          while (result.next()) {
            values.put(result.getString(1), result.getString(2));
          }
          return values;
        });
    var barcodeToSku = jdbc.query(
        "select barcode, sku_code from md_sku where barcode is not null",
        result -> {
          var values = new LinkedHashMap<String, String>();
          while (result.next()) {
            values.put(result.getString(1), result.getString(2));
          }
          return values;
        });
    return new ExistingProductIndex(
        spuCodes,
        skuToSpu.keySet(),
        barcodeToSku.keySet(),
        skuToSpu,
        barcodeToSku);
  }

  private String storeErrorWorkbook(
      String jobId,
      ProductWorkbookService.ProductPreflight preflight) {
    var bytes = workbooks.createErrorWorkbook(preflight);
    var key = "imports/" + jobId + "/errors.xlsx";
    objects.put(
        key,
        new ByteArrayInputStream(bytes),
        bytes.length,
        WORKBOOK_MEDIA_TYPE,
        sha256(bytes));
    return key;
  }

  private static CreateSku createSku(ValidProductRow row) {
    return new CreateSku(
        row.skuCode(),
        row.skuName(),
        row.barcode(),
        row.specifications(),
        row.unit());
  }

  private static byte[] readExactly(InputStream input, long declaredSize) {
    if (input == null) {
      throw new IllegalArgumentException("workbook is required");
    }
    try {
      var bytes = input.readNBytes((int) ProductWorkbookService.MAX_WORKBOOK_BYTES + 1);
      if (bytes.length != declaredSize) {
        throw new IllegalArgumentException("Declared workbook size does not match content");
      }
      return bytes;
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot read workbook", exception);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static java.time.Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static String safeMessage(RuntimeException exception) {
    var message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() <= 512 ? message : message.substring(0, 512);
  }

  private static void requireActor(AuditActor actor) {
    require(actor, "actor");
    requireText(actor.subject(), "actor.subject");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private static <T> T require(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
