package com.company.erp.masterdata.product;

import com.company.erp.masterdata.product.ProductViews.AuditView;
import com.company.erp.masterdata.product.ProductViews.ProductDetail;
import com.company.erp.masterdata.product.ProductViews.ProductFilter;
import com.company.erp.masterdata.product.ProductViews.ProductImageView;
import com.company.erp.masterdata.product.ProductViews.ProductPage;
import com.company.erp.masterdata.product.ProductViews.ProductSkuView;
import com.company.erp.masterdata.product.ProductViews.ProductSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcProductQueryService implements ProductQueryService {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
  };

  private final JdbcTemplate jdbc;

  public JdbcProductQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ProductDetail> get(String id) {
    var products = jdbc.query(
        """
        select s.id, s.spu_code, s.name, s.brand_id, b.name brand_name,
               s.category_id, c.name category_name, s.attributes, s.status,
               s.created_at, s.updated_at, s.version
        from md_spu s
        left join md_brand b on b.id = s.brand_id
        left join md_category c on c.id = s.category_id
        where s.id = ?
        """,
        (resultSet, rowNumber) -> new ProductDetail(
            resultSet.getString("id"),
            resultSet.getString("spu_code"),
            resultSet.getString("name"),
            resultSet.getString("brand_id"),
            resultSet.getString("brand_name"),
            resultSet.getString("category_id"),
            resultSet.getString("category_name"),
            map(resultSet.getString("attributes")),
            ProductStatus.valueOf(resultSet.getString("status")),
            skus(id),
            images(id),
            auditHistory(id),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("updated_at").toInstant(),
            resultSet.getLong("version")),
        id);
    return products.stream().findFirst();
  }

  @Override
  public ProductPage list(ProductFilter filter, int page, int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page must be non-negative");
    }
    if (size < 1 || size > 100) {
      throw new IllegalArgumentException("size must be between 1 and 100");
    }
    var where = new StringBuilder(" where 1 = 1");
    var args = new ArrayList<>();
    appendFilter(where, args, filter);
    var total = jdbc.queryForObject(
        "select count(distinct s.id) from md_spu s "
            + "left join md_sku k on k.spu_id = s.id"
            + where,
        Long.class,
        args.toArray());
    var pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add(page * size);
    var items = jdbc.query(
        """
        select s.id, s.spu_code, s.name, s.brand_id, b.name brand_name,
               s.category_id, c.name category_name, s.status, s.updated_at, s.version,
               (select count(*) from md_sku sk where sk.spu_id = s.id) sku_count,
               (select count(*) from md_channel_sku_mapping m
                  join md_sku sk on sk.sku_code = m.internal_sku_code
                  where sk.spu_id = s.id) mapping_count,
               (select object_key from md_product_image i
                  where i.spu_id = s.id order by display_order, id limit 1) main_image
        from md_spu s
        left join md_brand b on b.id = s.brand_id
        left join md_category c on c.id = s.category_id
        left join md_sku k on k.spu_id = s.id
        """
            + where
            + " group by s.id order by s.updated_at desc, s.id desc limit ? offset ?",
        (resultSet, rowNumber) -> summary(resultSet),
        pageArgs.toArray());
    return new ProductPage(items, page, size, total == null ? 0 : total);
  }

  private void appendFilter(StringBuilder where, List<Object> args, ProductFilter filter) {
    if (filter == null) {
      return;
    }
    if (text(filter.keyword())) {
      where.append(
          " and (s.spu_code = ? or k.sku_code = ? or s.name like ? escape '\\\\' "
              + "or k.name like ? escape '\\\\')");
      var keyword = filter.keyword().trim();
      var like = "%" + escapeLike(keyword) + "%";
      args.add(keyword);
      args.add(keyword);
      args.add(like);
      args.add(like);
    }
    if (text(filter.barcode())) {
      where.append(" and k.barcode = ?");
      args.add(filter.barcode().trim());
    }
    if (text(filter.brandId())) {
      where.append(" and s.brand_id = ?");
      args.add(filter.brandId().trim());
    }
    if (text(filter.categoryId())) {
      where.append(" and s.category_id = ?");
      args.add(filter.categoryId().trim());
    }
    if (filter.status() != null) {
      where.append(" and s.status = ?");
      args.add(filter.status().name());
    }
  }

  private List<ProductSkuView> skus(String productId) {
    return jdbc.query(
        """
        select id, sku_code, name, barcode, specifications, unit, status, version
        from md_sku where spu_id = ? order by sku_code
        """,
        (resultSet, rowNumber) -> new ProductSkuView(
            resultSet.getString("id"),
            resultSet.getString("sku_code"),
            resultSet.getString("name"),
            resultSet.getString("barcode"),
            map(resultSet.getString("specifications")),
            resultSet.getString("unit"),
            ProductStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("version")),
        productId);
  }

  private List<ProductImageView> images(String productId) {
    return jdbc.query(
        """
        select id, object_key, source_url, media_type, display_order
        from md_product_image where spu_id = ? order by display_order, id
        """,
        (resultSet, rowNumber) -> new ProductImageView(
            resultSet.getString("id"),
            resultSet.getString("object_key"),
            resultSet.getString("source_url"),
            resultSet.getString("media_type"),
            resultSet.getInt("display_order")),
        productId);
  }

  private List<AuditView> auditHistory(String productId) {
    return jdbc.query(
        """
        select action, actor, reason, before_json, after_json, created_at
        from audit_log
        where aggregate_type = 'PRODUCT' and aggregate_id = ?
        order by created_at desc, id desc
        """,
        (resultSet, rowNumber) -> new AuditView(
            resultSet.getString("action"),
            resultSet.getString("actor"),
            resultSet.getString("reason"),
            resultSet.getString("before_json"),
            resultSet.getString("after_json"),
            resultSet.getTimestamp("created_at").toInstant()),
        productId);
  }

  private static ProductSummary summary(ResultSet resultSet) throws SQLException {
    return new ProductSummary(
        resultSet.getString("id"),
        resultSet.getString("spu_code"),
        resultSet.getString("name"),
        resultSet.getString("brand_id"),
        resultSet.getString("brand_name"),
        resultSet.getString("category_id"),
        resultSet.getString("category_name"),
        ProductStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("sku_count"),
        resultSet.getInt("mapping_count"),
        resultSet.getString("main_image"),
        resultSet.getTimestamp("updated_at").toInstant(),
        resultSet.getLong("version"));
  }

  private static Map<String, String> map(String json) {
    if (!text(json)) {
      return Map.of();
    }
    try {
      return JSON.readValue(json, STRING_MAP);
    } catch (Exception exception) {
      throw new IllegalStateException("Stored product JSON is invalid", exception);
    }
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static boolean text(String value) {
    return value != null && !value.isBlank();
  }
}
