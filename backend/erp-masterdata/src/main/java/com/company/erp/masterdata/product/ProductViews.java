package com.company.erp.masterdata.product;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ProductViews {
  private ProductViews() {
  }

  public record ProductFilter(
      String keyword,
      String barcode,
      String brandId,
      String categoryId,
      ProductStatus status) {
  }

  public record ProductPage(
      List<ProductSummary> items,
      int page,
      int size,
      long total) {
    public ProductPage {
      items = List.copyOf(items);
    }
  }

  public record ProductSummary(
      String id,
      String spuCode,
      String name,
      String brandId,
      String brandName,
      String categoryId,
      String categoryName,
      ProductStatus status,
      int skuCount,
      int channelMappingCount,
      String mainImageUrl,
      Instant updatedAt,
      long version) {
  }

  public record ProductDetail(
      String id,
      String spuCode,
      String name,
      String brandId,
      String brandName,
      String categoryId,
      String categoryName,
      Map<String, String> attributes,
      ProductStatus status,
      List<ProductSkuView> skus,
      List<ProductImageView> images,
      List<AuditView> auditHistory,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    public ProductDetail {
      attributes = Map.copyOf(attributes);
      skus = List.copyOf(skus);
      images = List.copyOf(images);
      auditHistory = List.copyOf(auditHistory);
    }
  }

  public record ProductSkuView(
      String id,
      String skuCode,
      String name,
      String barcode,
      Map<String, String> specifications,
      String unit,
      ProductStatus status,
      long version) {
    public ProductSkuView {
      specifications = Map.copyOf(specifications);
    }
  }

  public record ProductImageView(
      String id,
      String objectKey,
      String sourceUrl,
      String mediaType,
      int displayOrder) {
  }

  public record AuditView(
      String action,
      String actor,
      String reason,
      String beforeJson,
      String afterJson,
      Instant createdAt) {
  }
}
