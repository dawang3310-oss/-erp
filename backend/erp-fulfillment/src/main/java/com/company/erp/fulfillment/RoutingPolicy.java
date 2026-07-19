package com.company.erp.fulfillment;

import java.util.List;

@FunctionalInterface
public interface RoutingPolicy {
  List<String> candidateWarehouses(
      String shopId,
      String receiverRegionCode,
      List<String> skuCodes);
}
