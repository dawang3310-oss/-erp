package com.company.erp.storage;

import com.company.erp.masterdata.product.ProductObjectStore;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.util.Map;

public final class MinioProductObjectStore implements ProductObjectStore {
  private final MinioClient client;
  private final String bucket;

  public MinioProductObjectStore(ProductStorageProperties properties) {
    require(properties.endpoint(), "endpoint");
    require(properties.accessKey(), "accessKey");
    require(properties.secretKey(), "secretKey");
    this.bucket = require(properties.productBucket(), "productBucket");
    this.client = MinioClient.builder()
        .endpoint(properties.endpoint())
        .credentials(properties.accessKey(), properties.secretKey())
        .build();
    ensureBucket();
  }

  @Override
  public StoredProductObject put(
      String objectKey,
      InputStream body,
      long size,
      String contentType,
      String sha256) {
    require(objectKey, "objectKey");
    require(contentType, "contentType");
    require(sha256, "sha256");
    if (body == null || size < 0) {
      throw new IllegalArgumentException("body and non-negative size are required");
    }
    try {
      client.putObject(PutObjectArgs.builder()
          .bucket(bucket)
          .object(objectKey)
          .stream(body, size, -1)
          .contentType(contentType)
          .userMetadata(Map.of("sha256", sha256))
          .build());
      return new StoredProductObject(objectKey, size, contentType, sha256);
    } catch (Exception exception) {
      throw storageFailure("put", objectKey, exception);
    }
  }

  @Override
  public InputStream get(String objectKey) {
    require(objectKey, "objectKey");
    try {
      return client.getObject(GetObjectArgs.builder()
          .bucket(bucket)
          .object(objectKey)
          .build());
    } catch (Exception exception) {
      throw storageFailure("get", objectKey, exception);
    }
  }

  @Override
  public boolean exists(String objectKey) {
    require(objectKey, "objectKey");
    try {
      client.statObject(StatObjectArgs.builder()
          .bucket(bucket)
          .object(objectKey)
          .build());
      return true;
    } catch (ErrorResponseException exception) {
      if ("NoSuchKey".equals(exception.errorResponse().code())) {
        return false;
      }
      throw storageFailure("stat", objectKey, exception);
    } catch (Exception exception) {
      throw storageFailure("stat", objectKey, exception);
    }
  }

  @Override
  public void deleteUnreferenced(String objectKey) {
    require(objectKey, "objectKey");
    try {
      client.removeObject(RemoveObjectArgs.builder()
          .bucket(bucket)
          .object(objectKey)
          .build());
    } catch (Exception exception) {
      throw storageFailure("delete", objectKey, exception);
    }
  }

  private void ensureBucket() {
    try {
      var exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!exists) {
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
    } catch (Exception exception) {
      throw new IllegalStateException("Product object bucket is unavailable: " + bucket, exception);
    }
  }

  private static IllegalStateException storageFailure(
      String operation,
      String objectKey,
      Exception cause) {
    return new IllegalStateException(
        "Product object storage " + operation + " failed: " + objectKey,
        cause);
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
