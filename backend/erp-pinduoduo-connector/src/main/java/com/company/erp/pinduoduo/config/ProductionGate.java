package com.company.erp.pinduoduo.config;

public final class ProductionGate {
  private final boolean productionEnabled;

  public ProductionGate(boolean productionEnabled) {
    this.productionEnabled = productionEnabled;
  }

  public void requireReadAllowed(Operation operation) {
    if (!productionEnabled && operation.productionBusinessData()) {
      throw new ProductionDisabledException(operation);
    }
  }

  public void requireSideEffectAllowed(Operation operation) {
    if (!productionEnabled) {
      throw new ProductionDisabledException(operation);
    }
  }

  public enum Operation {
    HEALTH_CHECK(false),
    CREDENTIAL_VALIDATION(false),
    SIMULATOR_VALIDATION(false),
    ORDER_PULL(true),
    INVENTORY_SYNC(true),
    SHIPMENT(true),
    AFTER_SALE(true),
    WAYBILL(true);

    private final boolean productionBusinessData;

    Operation(boolean productionBusinessData) {
      this.productionBusinessData = productionBusinessData;
    }

    public boolean productionBusinessData() {
      return productionBusinessData;
    }
  }

  public static final class ProductionDisabledException extends IllegalStateException {
    public ProductionDisabledException(Operation operation) {
      super("Pinduoduo production operation is disabled: " + operation);
    }
  }
}
