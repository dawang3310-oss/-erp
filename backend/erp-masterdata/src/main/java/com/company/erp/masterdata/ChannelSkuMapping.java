package com.company.erp.masterdata;

public record ChannelSkuMapping(
    String id,
    String platform,
    String shopId,
    String platformSkuId,
    String internalSkuCode) {
}
