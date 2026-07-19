package com.company.erp.shared;

public record DomainError(String code, String message) {
  public DomainError {
    if (code == null || code.isBlank() || message == null || message.isBlank()) {
      throw new IllegalArgumentException("code and message are required");
    }
  }
}
