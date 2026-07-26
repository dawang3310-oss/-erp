package com.company.erp.masterdata.product;

import static com.company.erp.masterdata.product.ProductErrors.ProductConflictException;
import static com.company.erp.masterdata.product.ProductErrors.ProductNotFoundException;
import static com.company.erp.masterdata.product.ProductErrors.StaleProductVersionException;
import static com.company.erp.masterdata.product.ProductStatus.ACTIVE;
import static com.company.erp.masterdata.product.ProductStatus.ARCHIVED;
import static com.company.erp.masterdata.product.ProductStatus.DISABLED;
import static com.company.erp.masterdata.product.ProductStatus.DRAFT;

import com.company.erp.masterdata.product.ProductCommands.AuditActor;
import com.company.erp.masterdata.product.ProductCommands.CreateSku;
import com.company.erp.masterdata.product.ProductCommands.CreateSpu;
import com.company.erp.masterdata.product.ProductCommands.UpdateSpu;
import com.company.erp.shared.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcProductCatalogService implements ProductCatalogService {
  private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
  private static final Map<ProductStatus, Set<ProductStatus>> TRANSITIONS = Map.of(
      DRAFT, Set.of(ACTIVE, ARCHIVED),
      ACTIVE, Set.of(DISABLED, ARCHIVED),
      DISABLED, Set.of(ACTIVE, ARCHIVED),
      ARCHIVED, Set.of());
  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ProductObjectStore objects;

  public JdbcProductCatalogService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this(jdbc, transactionManager, null);
  }

  public JdbcProductCatalogService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      ProductObjectStore objects) {
    this.jdbc = require(jdbc, "jdbc");
    this.transactions = new TransactionTemplate(require(transactionManager, "transactionManager"));
    this.objects = objects;
  }

  @Override
  public String createSpu(CreateSpu command, AuditActor actor) {
    require(command, "command");
    requireActor(actor);
    requireText(command.spuCode(), "spuCode");
    requireText(command.name(), "name");
    if (command.skus().isEmpty()) {
      throw new IllegalArgumentException("At least one SKU is required");
    }
    return transactions.execute(status -> {
      ensureSpuCodeAvailable(command.spuCode());
      command.skus().forEach(this::ensureSkuAvailable);
      var productId = Ids.newId();
      try {
        jdbc.update(
            """
            insert into md_spu
              (id, spu_code, name, brand_id, category_id, attributes, status)
            values (?, ?, ?, ?, ?, ?, 'DRAFT')
            """,
            productId,
            command.spuCode().trim(),
            command.name().trim(),
            normalizeNullable(command.brandId()),
            normalizeNullable(command.categoryId()),
            json(command.attributes()));
        for (var sku : command.skus()) {
          insertSku(productId, sku);
        }
      } catch (DuplicateKeyException exception) {
        throw new ProductConflictException("PRODUCT_CONFLICT", command.spuCode());
      }
      audit(productId, "PRODUCT_CREATED", actor, "创建商品", null, json(command));
      return productId;
    });
  }

  @Override
  public void updateSpu(String id, UpdateSpu command, AuditActor actor) {
    requireText(id, "id");
    require(command, "command");
    requireActor(actor);
    requireText(command.name(), "name");
    if (command.skus().isEmpty() && command.newSkus().isEmpty()) {
      throw new IllegalArgumentException("At least one SKU is required");
    }
    transactions.executeWithoutResult(status -> {
      var currentStatus = requireCurrentProduct(id, command.version());
      if (currentStatus == ARCHIVED) {
        throw new IllegalArgumentException("Archived products cannot be edited");
      }
      var before = jdbc.queryForObject(
          """
          select json_object(
            'name', name,
            'brandId', brand_id,
            'categoryId', category_id,
            'attributes', attributes,
            'version', version)
          from md_spu where id = ?
          """,
          String.class,
          id);
      var changed = jdbc.update(
          """
          update md_spu
          set name = ?, brand_id = ?, category_id = ?, attributes = ?, version = version + 1
          where id = ? and version = ?
          """,
          command.name().trim(),
          normalizeNullable(command.brandId()),
          normalizeNullable(command.categoryId()),
          json(command.attributes()),
          id,
          command.version());
      if (changed == 0) {
        throw new StaleProductVersionException(id);
      }
      for (var sku : command.skus()) {
        updateSku(id, sku);
      }
      for (var sku : command.newSkus()) {
        ensureSkuAvailable(sku);
        insertSku(id, sku);
      }
      audit(id, "PRODUCT_UPDATED", actor, "修改商品资料", before, json(command));
    });
  }

  @Override
  public void changeStatus(
      String id,
      ProductStatus target,
      long version,
      String reason,
      AuditActor actor) {
    requireText(id, "id");
    require(target, "target");
    requireText(reason, "reason");
    requireActor(actor);
    transactions.executeWithoutResult(status -> {
      var current = requireCurrentProduct(id, version);
      if (!TRANSITIONS.get(current).contains(target)) {
        throw new IllegalArgumentException(
            "Product status transition is not allowed: " + current + " -> " + target);
      }
      var changed = jdbc.update(
          """
          update md_spu
          set status = ?, version = version + 1
          where id = ? and version = ?
          """,
          target.name(),
          id,
          version);
      if (changed == 0) {
        throw new StaleProductVersionException(id);
      }
      jdbc.update(
          "update md_sku set status = ?, version = version + 1 where spu_id = ?",
          target.name(),
          id);
      audit(
          id,
          "PRODUCT_STATUS_CHANGED",
          actor,
          reason.trim(),
          json(Map.of("status", current.name())),
          json(Map.of("status", target.name())));
    });
  }

  @Override
  public String addImage(
      String id,
      InputStream image,
      long size,
      String declaredMediaType,
      long version,
      AuditActor actor) {
    requireText(id, "id");
    requireActor(actor);
    var store = requireImageStore();
    var bytes = readImage(image, size);
    var format = detectFormat(bytes);
    var checksum = sha256(bytes);
    var objectKey = "products/" + id + "/" + checksum + "." + format.extension();
    try {
      return transactions.execute(status -> {
        requireCurrentProduct(id, version);
        var existing = jdbc.query(
            """
            select id from md_product_image
            where spu_id = ? and object_key = ?
            """,
            (result, row) -> result.getString(1),
            id,
            objectKey);
        if (!existing.isEmpty()) {
          return existing.getFirst();
        }
        store.put(
            objectKey,
            new java.io.ByteArrayInputStream(bytes),
            bytes.length,
            format.mediaType(),
            checksum);
        bumpVersion(id, version);
        var imageId = Ids.newId();
        var displayOrder = jdbc.queryForObject(
            """
            select coalesce(max(display_order), -1) + 1
            from md_product_image where spu_id = ?
            """,
            Integer.class,
            id);
        jdbc.update(
            """
            insert into md_product_image
              (id, spu_id, object_key, content_sha256, media_type, display_order)
            values (?, ?, ?, ?, ?, ?)
            """,
            imageId,
            id,
            objectKey,
            checksum,
            format.mediaType(),
            displayOrder);
        audit(
            id,
            "PRODUCT_IMAGE_ADDED",
            actor,
            "Add product image",
            null,
            json(Map.of(
                "imageId", imageId,
                "objectKey", objectKey,
                "mediaType", format.mediaType())));
        return imageId;
      });
    } catch (RuntimeException exception) {
      if (jdbc.queryForObject(
          "select count(*) from md_product_image where object_key = ?",
          Integer.class,
          objectKey) == 0) {
        store.deleteUnreferenced(objectKey);
      }
      throw exception;
    }
  }

  @Override
  public void reorderImages(
      String id,
      List<String> imageIds,
      long version,
      AuditActor actor) {
    requireText(id, "id");
    require(imageIds, "imageIds");
    requireActor(actor);
    transactions.executeWithoutResult(status -> {
      requireCurrentProduct(id, version);
      var current = jdbc.queryForList(
          """
          select id from md_product_image
          where spu_id = ? order by display_order, id
          """,
          String.class,
          id);
      if (imageIds.size() != current.size()
          || new HashSet<>(imageIds).size() != imageIds.size()
          || !new HashSet<>(imageIds).equals(new HashSet<>(current))) {
        throw new IllegalArgumentException("Image order must contain every product image exactly once");
      }
      for (var index = 0; index < imageIds.size(); index++) {
        jdbc.update(
            """
            update md_product_image set display_order = ?
            where id = ? and spu_id = ?
            """,
            index,
            imageIds.get(index),
            id);
      }
      bumpVersion(id, version);
      audit(
          id,
          "PRODUCT_IMAGE_REORDERED",
          actor,
          "Reorder product images",
          json(Map.of("imageIds", current)),
          json(Map.of("imageIds", imageIds)));
    });
  }

  @Override
  public void removeImage(
      String id,
      String imageId,
      long version,
      String reason,
      AuditActor actor) {
    requireText(id, "id");
    requireText(imageId, "imageId");
    requireText(reason, "reason");
    requireActor(actor);
    var objectKey = transactions.execute(status -> {
      requireCurrentProduct(id, version);
      var keys = jdbc.query(
          """
          select object_key from md_product_image
          where id = ? and spu_id = ?
          """,
          (result, row) -> result.getString(1),
          imageId,
          id);
      if (keys.isEmpty()) {
        throw new IllegalArgumentException("Product image not found: " + imageId);
      }
      jdbc.update(
          "delete from md_product_image where id = ? and spu_id = ?",
          imageId,
          id);
      bumpVersion(id, version);
      audit(
          id,
          "PRODUCT_IMAGE_REMOVED",
          actor,
          reason.trim(),
          json(Map.of("imageId", imageId, "objectKey", keys.getFirst())),
          null);
      return keys.getFirst();
    });
    var references = jdbc.queryForObject(
        "select count(*) from md_product_image where object_key = ?",
        Integer.class,
        objectKey);
    if (references != null && references == 0) {
      requireImageStore().deleteUnreferenced(objectKey);
    }
  }

  private void bumpVersion(String id, long version) {
    var changed = jdbc.update(
        """
        update md_spu set version = version + 1
        where id = ? and version = ?
        """,
        id,
        version);
    if (changed == 0) {
      throw new StaleProductVersionException(id);
    }
  }

  private ProductObjectStore requireImageStore() {
    if (objects == null) {
      throw new IllegalStateException("Product object store is required for image operations");
    }
    return objects;
  }

  private static byte[] readImage(InputStream image, long declaredSize) {
    if (image == null || declaredSize < 0 || declaredSize > MAX_IMAGE_BYTES) {
      throw new IllegalArgumentException("Product image cannot exceed 10 MiB");
    }
    try {
      var bytes = image.readNBytes((int) MAX_IMAGE_BYTES + 1);
      if (bytes.length > MAX_IMAGE_BYTES || bytes.length != declaredSize) {
        throw new IllegalArgumentException("Product image size is invalid");
      }
      return bytes;
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot read product image", exception);
    }
  }

  private static ImageFormat detectFormat(byte[] bytes) {
    if (startsWith(bytes, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
      return new ImageFormat("png", "image/png");
    }
    if (startsWith(bytes, new int[] {0xff, 0xd8, 0xff})) {
      return new ImageFormat("jpg", "image/jpeg");
    }
    if (bytes.length >= 12
        && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
      return new ImageFormat("webp", "image/webp");
    }
    throw new IllegalArgumentException("Product image must be JPEG, PNG, or WebP");
  }

  private static boolean startsWith(byte[] bytes, int[] signature) {
    if (bytes.length < signature.length) {
      return false;
    }
    for (var index = 0; index < signature.length; index++) {
      if ((bytes[index] & 0xff) != signature[index]) {
        return false;
      }
    }
    return true;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record ImageFormat(String extension, String mediaType) {
  }

  private void insertSku(String productId, CreateSku sku) {
    jdbc.update(
        """
        insert into md_sku
          (id, spu_id, sku_code, name, barcode, specifications, unit,
           batch_enabled, serial_enabled, status)
        values (?, ?, ?, ?, ?, ?, ?, false, false, 'DRAFT')
        """,
        Ids.newId(),
        productId,
        requireText(sku.skuCode(), "skuCode"),
        requireText(sku.name(), "skuName"),
        normalizeNullable(sku.barcode()),
        json(sku.specifications()),
        requireText(sku.unit(), "unit"));
  }

  private void updateSku(String productId, ProductCommands.UpdateSku sku) {
    requireText(sku.id(), "sku.id");
    requireText(sku.name(), "sku.name");
    requireText(sku.unit(), "sku.unit");
    require(sku.status(), "sku.status");
    var barcode = normalizeNullable(sku.barcode());
    if (barcode != null) {
      var duplicates = jdbc.queryForObject(
          "select count(*) from md_sku where barcode = ? and id <> ?",
          Integer.class,
          barcode,
          sku.id());
      if (duplicates != null && duplicates > 0) {
        throw new ProductConflictException("DUPLICATE_BARCODE", barcode);
      }
    }
    var changed = jdbc.update(
        """
        update md_sku
        set name = ?, barcode = ?, specifications = ?, unit = ?, status = ?,
            version = version + 1
        where id = ? and spu_id = ? and version = ?
        """,
        sku.name().trim(),
        barcode,
        json(sku.specifications()),
        sku.unit().trim(),
        sku.status().name(),
        sku.id(),
        productId,
        sku.version());
    if (changed == 0) {
      throw new StaleProductVersionException(productId);
    }
  }

  private void ensureSpuCodeAvailable(String spuCode) {
    if (count("select count(*) from md_spu where spu_code = ?", spuCode.trim()) > 0) {
      throw new ProductConflictException("DUPLICATE_SPU", spuCode);
    }
  }

  private void ensureSkuAvailable(CreateSku sku) {
    var skuCode = requireText(sku.skuCode(), "skuCode");
    if (count("select count(*) from md_sku where sku_code = ?", skuCode) > 0) {
      throw new ProductConflictException("DUPLICATE_SKU", skuCode);
    }
    var barcode = normalizeNullable(sku.barcode());
    if (barcode != null
        && count("select count(*) from md_sku where barcode = ?", barcode) > 0) {
      throw new ProductConflictException("DUPLICATE_BARCODE", barcode);
    }
  }

  private ProductStatus requireCurrentProduct(String id, long version) {
    var rows = jdbc.query(
        "select status, version from md_spu where id = ?",
        (resultSet, rowNumber) -> Map.entry(
            ProductStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("version")),
        id);
    if (rows.isEmpty()) {
      throw new ProductNotFoundException(id);
    }
    var row = rows.getFirst();
    if (row.getValue() != version) {
      throw new StaleProductVersionException(id);
    }
    return row.getKey();
  }

  private void audit(
      String aggregateId,
      String action,
      AuditActor actor,
      String reason,
      String beforeJson,
      String afterJson) {
    jdbc.update(
        """
        insert into audit_log
          (id, aggregate_type, aggregate_id, action, actor, reason, before_json, after_json)
        values (?, 'PRODUCT', ?, ?, ?, ?, ?, ?)
        """,
        Ids.newId(),
        aggregateId,
        action,
        actor.subject(),
        reason,
        beforeJson,
        afterJson);
  }

  private int count(String sql, String value) {
    return jdbc.queryForObject(sql, Integer.class, value);
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Value cannot be serialized", exception);
    }
  }

  private static void requireActor(AuditActor actor) {
    require(actor, "actor");
    requireText(actor.subject(), "actor.subject");
  }

  private static String normalizeNullable(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }

  private static <T> T require(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
