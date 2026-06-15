package com.masonx.paygateway.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;

public class S3FileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorageService.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String prefix;
    private final long presignedUrlExpirySeconds;

    public S3FileStorageService(S3Client s3, S3Presigner presigner,
                                 String bucket, String prefix, long presignedUrlExpirySeconds) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.prefix = prefix;
        this.presignedUrlExpirySeconds = presignedUrlExpirySeconds;
        log.info("S3 file storage initialised — bucket={} prefix={}", bucket, prefix);
    }

    @Override
    public String store(String keyPath, InputStream data, long contentLength, String contentType) {
        String s3Key = fullKey(keyPath);
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(s3Key)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build(),
                RequestBody.fromInputStream(data, contentLength));
        return keyPath;
    }

    @Override
    public String getServeUrl(String keyPath) {
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignedUrlExpirySeconds))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(fullKey(keyPath))
                        .build())
                .build();
        return presigner.presignGetObject(req).url().toString();
    }

    @Override
    public byte[] read(String keyPath) {
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket)
                .key(fullKey(keyPath))
                .build()).asByteArray();
    }

    @Override
    public void delete(String keyPath) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(fullKey(keyPath))
                    .build());
        } catch (Exception e) {
            log.warn("Failed to delete S3 object {}: {}", keyPath, e.getMessage());
        }
    }

    private String fullKey(String keyPath) {
        return prefix.isBlank() ? keyPath : (prefix + "/" + keyPath);
    }
}
