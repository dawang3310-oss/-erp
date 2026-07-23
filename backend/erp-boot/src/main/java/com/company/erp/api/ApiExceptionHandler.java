package com.company.erp.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  ApiError invalidRequest(IllegalArgumentException exception) {
    return new ApiError("INVALID_REQUEST", exception.getMessage());
  }

  record ApiError(String code, String message) {
  }
}
