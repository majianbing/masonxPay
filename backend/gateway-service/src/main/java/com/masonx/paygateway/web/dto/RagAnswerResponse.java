package com.masonx.paygateway.web.dto;

import java.util.List;

public record RagAnswerResponse(
        String answer,
        List<Citation> citations,
        String refusalReason,
        String confidence,
        List<RetrievedChunk> retrievedChunks,
        String promptTemplateVersion,
        String answerPolicyVersion,
        String modelProvider,
        String modelName
) {
    public record Citation(
            String sourcePath,
            String headingPath,
            String sourceType,
            String stability
    ) {
    }

    public record RetrievedChunk(
            String chunkId,
            String text,
            double score,
            Citation citation
    ) {
    }
}
