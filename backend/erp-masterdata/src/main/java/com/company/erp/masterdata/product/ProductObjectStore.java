package com.company.erp.masterdata.product;

import java.io.InputStream;

public interface ProductObjectStore {
  StoredProductObject put(
      String objectKey,
      InputStream body,
      long size,
      String contentType,
      String sha256);

  InputStream get(String objectKey);

  boolean exists(String objectKey);

  void deleteUnreferenced(String objectKey);

  record StoredProductObject(
      String objectKey,
      long size,
      String contentType,
      String sha256) {
  }
}
