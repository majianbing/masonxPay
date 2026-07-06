package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagAnswerRequest(
        @NotBlank @Size(max = 2_000) String question,
        @NotBlank String audience,
        @Min(1) @Max(8) Integer maxCitations,
        @Size(max = 128) String correlationId
) {
}
