package com.company.erp.masterdata.product;

public final class ProductErrors {
  private ProductErrors() {
  }

  public static final class ProductConflictException extends IllegalStateException {
    private final String code;

    public ProductConflictException(String code, String value) {
      super(code + ": " + value);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  public static final class StaleProductVersionException extends IllegalStateException {
    public StaleProductVersionException(String productId) {
      super("Stale product version: " + productId);
    }
  }

  public static final class ProductNotFoundException extends IllegalArgumentException {
    public ProductNotFoundException(String productId) {
      super("Product not found: " + productId);
    }
  }
}
