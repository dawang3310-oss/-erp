package com.company.erp.storage;

public record ProductStorageProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String productBucket) {
}
