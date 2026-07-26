package com.company.erp.masterdata.product;

import com.company.erp.masterdata.product.ProductCommands.AuditActor;
import com.company.erp.masterdata.product.ProductCommands.CreateSpu;
import com.company.erp.masterdata.product.ProductCommands.UpdateSpu;
import java.io.InputStream;
import java.util.List;

public interface ProductCatalogService {
  String createSpu(CreateSpu command, AuditActor actor);

  void updateSpu(String id, UpdateSpu command, AuditActor actor);

  void changeStatus(
      String id,
      ProductStatus target,
      long version,
      String reason,
      AuditActor actor);

  String addImage(
      String id,
      InputStream image,
      long size,
      String declaredMediaType,
      long version,
      AuditActor actor);

  void reorderImages(
      String id,
      List<String> imageIds,
      long version,
      AuditActor actor);

  void removeImage(
      String id,
      String imageId,
      long version,
      String reason,
      AuditActor actor);
}
