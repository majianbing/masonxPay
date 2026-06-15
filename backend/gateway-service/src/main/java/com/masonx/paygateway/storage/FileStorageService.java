package com.masonx.paygateway.storage;

import java.io.InputStream;

public interface FileStorageService {

    /**
     * Stores the incoming stream at {@code keyPath} and returns the storage key.
     * keyPath must be tenant-scoped, e.g. "disputes/{merchantId}/{disputeId}/{uuid}.pdf"
     */
    String store(String keyPath, InputStream data, long contentLength, String contentType);

    /** Returns a URL through which the file can be accessed (presigned S3 URL or local serve path). */
    String getServeUrl(String keyPath);

    /** Reads file bytes back (used when re-uploading to provider APIs). */
    byte[] read(String keyPath);

    void delete(String keyPath);
}
