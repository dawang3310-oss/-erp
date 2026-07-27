package com.company.erp.api;

import com.company.erp.masterdata.product.ProductErrors.ProductConflictException;
import com.company.erp.masterdata.product.ProductErrors.ProductNotFoundException;
import com.company.erp.masterdata.product.ProductErrors.StaleProductVersionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ProductConflictException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  ApiError productConflict(ProductConflictException exception) {
    return new ApiError("PRODUCT_CONFLICT", exception.getMessage());
  }

  @ExceptionHandler(StaleProductVersionException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  ApiError staleProductVersion(StaleProductVersionException exception) {
    return new ApiError("STALE_PRODUCT_VERSION", exception.getMessage());
  }

  @ExceptionHandler(ProductNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  ApiError productNotFound(ProductNotFoundException exception) {
    return new ApiError("PRODUCT_NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError invalidRequest(IllegalArgumentException exception) {
    return new ApiError("INVALID_REQUEST", exception.getMessage());
  }

  record ApiError(String code, String message) {
  }
}
