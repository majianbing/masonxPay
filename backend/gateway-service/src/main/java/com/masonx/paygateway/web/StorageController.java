package com.masonx.paygateway.web;

import com.masonx.paygateway.storage.LocalFileStorageService;
import com.masonx.paygateway.storage.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Serves locally-stored files (dispute evidence, etc.) when S3 is not configured.
 * S3 files are served directly via presigned URLs and do not pass through this controller.
 */
@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

    private final FileStorageService storageService;

    public StorageController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/disputes/{merchantId}/**")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'DISPUTE', 'READ')")
    public ResponseEntity<byte[]> serveDisputeFile(
            @PathVariable UUID merchantId,
            HttpServletRequest request) {

        // Only active for local storage; S3 uses presigned URLs
        if (!(storageService instanceof LocalFileStorageService)) {
            return ResponseEntity.notFound().build();
        }

        String fullPath = request.getRequestURI();
        String prefix = "/api/v1/storage/";
        String keyPath = fullPath.substring(fullPath.indexOf(prefix) + prefix.length());

        byte[] data = storageService.read(keyPath);
        String contentType = guessContentType(keyPath);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
    }

    private String guessContentType(String keyPath) {
        String lower = keyPath.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
