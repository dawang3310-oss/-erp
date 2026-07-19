package com.company.erp.masterdata;

import com.company.erp.shared.Ids;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcMasterDataService implements MasterDataService {
  private final JdbcTemplate jdbc;

  public JdbcMasterDataService(JdbcTemplate jdbc) {
    this.jdbc = require(jdbc, "jdbc");
  }

  @Override
  public String createShop(String platform, String shopCode, String name) {
    var id = Ids.newId();
    jdbc.update(
        "insert into md_shop (id, platform, shop_code, name) values (?, ?, ?, ?)",
        id,
        requireText(platform, "platform"),
        requireText(shopCode, "shopCode"),
        requireText(name, "name"));
    return id;
  }

  @Override
  public String createWarehouse(String warehouseCode, String name, String fulfillmentType) {
    var id = Ids.newId();
    jdbc.update(
        "insert into md_warehouse (id, warehouse_code, name, fulfillment_type) values (?, ?, ?, ?)",
        id,
        requireText(warehouseCode, "warehouseCode"),
        requireText(name, "name"),
        requireText(fulfillmentType, "fulfillmentType"));
    return id;
  }

  @Override
  public String createSku(String skuCode, String barcode, boolean batchEnabled, boolean serialEnabled) {
    var id = Ids.newId();
    jdbc.update(
        "insert into md_sku (id, sku_code, barcode, batch_enabled, serial_enabled) values (?, ?, ?, ?, ?)",
        id,
        requireText(skuCode, "skuCode"),
        normalizeNullable(barcode),
        batchEnabled,
        serialEnabled);
    return id;
  }

  @Override
  public ChannelSkuMapping mapChannelSku(
      String platform,
      String shopId,
      String platformSkuId,
      String internalSkuCode) {
    var mapping = new ChannelSkuMapping(
        Ids.newId(),
        requireText(platform, "platform"),
        requireText(shopId, "shopId"),
        requireText(platformSkuId, "platformSkuId"),
        requireText(internalSkuCode, "internalSkuCode"));
    jdbc.update(
        "insert into md_channel_sku_mapping "
            + "(id, platform, shop_id, platform_sku_id, internal_sku_code) values (?, ?, ?, ?, ?)",
        mapping.id(),
        mapping.platform(),
        mapping.shopId(),
        mapping.platformSkuId(),
        mapping.internalSkuCode());
    return mapping;
  }

  @Override
  public Optional<String> resolveInternalSku(String platform, String shopId, String platformSkuId) {
    List<String> matches = jdbc.query(
        "select internal_sku_code from md_channel_sku_mapping "
            + "where platform = ? and shop_id = ? and platform_sku_id = ?",
        (resultSet, rowNumber) -> resultSet.getString(1),
        requireText(platform, "platform"),
        requireText(shopId, "shopId"),
        requireText(platformSkuId, "platformSkuId"));
    return matches.stream().findFirst();
  }

  private static String normalizeNullable(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static <T> T require(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
