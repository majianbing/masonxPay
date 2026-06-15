package com.masonx.paygateway.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public FileStorageService fileStorageService(
            StorageProperties props,
            @Value("${app.api-base-url:http://localhost:8080}") String apiBaseUrl) {

        boolean useS3 = "s3".equalsIgnoreCase(props.getType())
                && props.getS3().getBucket() != null
                && !props.getS3().getBucket().isBlank();

        if (useS3) {
            Region region = Region.of(props.getS3().getRegion());
            S3Client s3 = S3Client.builder().region(region).build();
            S3Presigner presigner = S3Presigner.builder().region(region).build();
            return new S3FileStorageService(s3, presigner,
                    props.getS3().getBucket(),
                    props.getS3().getPrefix(),
                    props.getS3().getPresignedUrlExpirySeconds());
        }

        if ("s3".equalsIgnoreCase(props.getType())) {
            log.warn("Storage type is 's3' but STORAGE_S3_BUCKET is not set — falling back to local storage");
        }

        return new LocalFileStorageService(props.getLocal().getBaseDir(), apiBaseUrl);
    }
}
