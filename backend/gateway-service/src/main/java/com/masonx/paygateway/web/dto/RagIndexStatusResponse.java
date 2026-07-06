package com.masonx.paygateway.web.dto;

import java.util.List;

public record RagIndexStatusResponse(
        String indexVersion,
        String gitCommit,
        String lastIndexedAt,
        int chunkCount,
        int sourceCount,
        String vectorBackend,
        List<SourceSummary> sources
) {
    public record SourceSummary(
            String sourcePath,
            int chunkCount,
            String sourceSha256,
            String sourceType,
            String stability
    ) {
    }
}
