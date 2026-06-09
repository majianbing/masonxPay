package com.masonx.paygateway.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class LocalFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    private final Path baseDir;
    private final String apiBaseUrl;

    public LocalFileStorageService(String baseDir,
                                    @Value("${app.api-base-url:http://localhost:8080}") String apiBaseUrl) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.apiBaseUrl = apiBaseUrl;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory: " + baseDir, e);
        }
        log.info("Local file storage initialised at {}", this.baseDir);
    }

    @Override
    public String store(String keyPath, InputStream data, long contentLength, String contentType) {
        Path target = resolve(keyPath);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file at key " + keyPath, e);
        }
        return keyPath;
    }

    @Override
    public String getServeUrl(String keyPath) {
        return apiBaseUrl + "/api/v1/storage/" + keyPath;
    }

    @Override
    public byte[] read(String keyPath) {
        try {
            return Files.readAllBytes(resolve(keyPath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file at key " + keyPath, e);
        }
    }

    @Override
    public void delete(String keyPath) {
        try {
            Files.deleteIfExists(resolve(keyPath));
        } catch (IOException e) {
            log.warn("Failed to delete file at key {}: {}", keyPath, e.getMessage());
        }
    }

    /** Resolves keyPath under baseDir and validates no path traversal. */
    private Path resolve(String keyPath) {
        Path resolved = baseDir.resolve(keyPath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid storage key path: " + keyPath);
        }
        return resolved;
    }
}
