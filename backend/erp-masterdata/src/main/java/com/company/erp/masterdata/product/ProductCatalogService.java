package com.company.erp.masterdata.product;

import com.company.erp.masterdata.product.ProductCommands.AuditActor;
import com.company.erp.masterdata.product.ProductCommands.CreateSpu;
import com.company.erp.masterdata.product.ProductCommands.UpdateSpu;

public interface ProductCatalogService {
  String createSpu(CreateSpu command, AuditActor actor);

  void updateSpu(String id, UpdateSpu command, AuditActor actor);

  void changeStatus(
      String id,
      ProductStatus target,
      long version,
      String reason,
      AuditActor actor);
}
