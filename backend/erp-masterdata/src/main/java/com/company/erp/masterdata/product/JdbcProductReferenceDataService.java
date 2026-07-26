package com.company.erp.masterdata.product;

import com.company.erp.masterdata.product.ProductErrors.ProductConflictException;
import com.company.erp.shared.Ids;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcProductReferenceDataService implements ProductReferenceDataService {
  private final JdbcTemplate jdbc;

  public JdbcProductReferenceDataService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ReferenceItem> findBrands(String query) {
    var search = normalizedQuery(query);
    return jdbc.query(
        """
        select id, name
        from md_brand
        where status = 'ACTIVE' and (? = '' or name like ? escape '!')
        order by name, id
        limit 50
        """,
        (resultSet, rowNumber) -> new ReferenceItem(
            resultSet.getString("id"),
            resultSet.getString("name")),
        search,
        "%" + escapeLike(search) + "%");
  }

  @Override
  public ReferenceItem createBrand(String name) {
    var normalized = normalizedName(name);
    if (exists("select count(*) from md_brand where name = ?", normalized)) {
      throw new ProductConflictException("DUPLICATE_BRAND", normalized);
    }
    var id = Ids.newId();
    try {
      jdbc.update("insert into md_brand (id, name) values (?, ?)", id, normalized);
    } catch (DataIntegrityViolationException exception) {
      throw new ProductConflictException("DUPLICATE_BRAND", normalized);
    }
    return new ReferenceItem(id, normalized);
  }

  @Override
  public List<CategoryItem> findCategories(String query) {
    var search = normalizedQuery(query);
    return jdbc.query(
        """
        select id, name, parent_id, path
        from md_category
        where status = 'ACTIVE' and (? = '' or name like ? escape '!')
        order by path, name, id
        limit 50
        """,
        (resultSet, rowNumber) -> new CategoryItem(
            resultSet.getString("id"),
            resultSet.getString("name"),
            resultSet.getString("parent_id"),
            resultSet.getString("path")),
        search,
        "%" + escapeLike(search) + "%");
  }

  @Override
  public CategoryItem createCategory(String name, String parentId) {
    var normalized = normalizedName(name);
    var normalizedParent = text(parentId) ? parentId.trim() : null;
    String parentPath = "";
    if (normalizedParent != null) {
      var paths = jdbc.query(
          "select path from md_category where id = ? and status = 'ACTIVE'",
          (resultSet, rowNumber) -> resultSet.getString("path"),
          normalizedParent);
      if (paths.isEmpty()) {
        throw new IllegalArgumentException("Category parent not found: " + normalizedParent);
      }
      parentPath = paths.getFirst();
    }
    var duplicateSql = normalizedParent == null
        ? "select count(*) from md_category where parent_id is null and name = ?"
        : "select count(*) from md_category where parent_id = ? and name = ?";
    var duplicate = normalizedParent == null
        ? exists(duplicateSql, normalized)
        : exists(duplicateSql, normalizedParent, normalized);
    if (duplicate) {
      throw new ProductConflictException("DUPLICATE_CATEGORY", normalized);
    }

    var id = Ids.newId();
    var path = parentPath + "/" + id;
    try {
      jdbc.update(
          "insert into md_category (id, parent_id, name, path) values (?, ?, ?, ?)",
          id,
          normalizedParent,
          normalized,
          path);
    } catch (DataIntegrityViolationException exception) {
      throw new ProductConflictException("DUPLICATE_CATEGORY", normalized);
    }
    return new CategoryItem(id, normalized, normalizedParent, path);
  }

  private boolean exists(String sql, Object... arguments) {
    var count = jdbc.queryForObject(sql, Integer.class, arguments);
    return count != null && count > 0;
  }

  private static String normalizedName(String value) {
    if (!text(value)) {
      throw new IllegalArgumentException("Reference data name is required");
    }
    var normalized = value.trim();
    if (normalized.length() > 128) {
      throw new IllegalArgumentException("Reference data name is too long");
    }
    return normalized;
  }

  private static String normalizedQuery(String value) {
    return text(value) ? value.trim() : "";
  }

  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static boolean text(String value) {
    return value != null && !value.isBlank();
  }
}
