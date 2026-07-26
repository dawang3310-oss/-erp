package com.company.erp.masterdata.product;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProductCommands {
  private ProductCommands() {
  }

  public record CreateSpu(
      String spuCode,
      String name,
      String brandId,
      String categoryId,
      Map<String, String> attributes,
      List<CreateSku> skus) {
    public CreateSpu {
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
      skus = skus == null ? List.of() : List.copyOf(skus);
    }
  }

  public record UpdateSpu(
      String name,
      String brandId,
      String categoryId,
      Map<String, String> attributes,
      List<UpdateSku> skus,
      long version) {
    public UpdateSpu {
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
      skus = skus == null ? List.of() : List.copyOf(skus);
    }
  }

  public record CreateSku(
      String skuCode,
      String name,
      String barcode,
      Map<String, String> specifications,
      String unit) {
    public CreateSku {
      specifications = specifications == null ? Map.of() : Map.copyOf(specifications);
    }
  }

  public record UpdateSku(
      String id,
      String name,
      String barcode,
      Map<String, String> specifications,
      String unit,
      ProductStatus status,
      long version) {
    public UpdateSku {
      specifications = specifications == null ? Map.of() : Map.copyOf(specifications);
    }
  }

  public record AuditActor(String subject, Set<String> roles) {
    public AuditActor {
      roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
  }
}
