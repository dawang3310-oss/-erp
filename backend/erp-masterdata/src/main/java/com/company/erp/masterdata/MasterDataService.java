package com.company.erp.masterdata;

import java.util.Optional;

public interface MasterDataService {
  String createShop(String platform, String shopCode, String name);

  String createWarehouse(String warehouseCode, String name, String fulfillmentType);

  String createSku(String skuCode, String barcode, boolean batchEnabled, boolean serialEnabled);

  ChannelSkuMapping mapChannelSku(
      String platform,
      String shopId,
      String platformSkuId,
      String internalSkuCode);

  Optional<String> resolveInternalSku(String platform, String shopId, String platformSkuId);
}
