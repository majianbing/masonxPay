package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.Positive;

public record CreateRefundRequest(
        @Positive Long amount,
        String reason
) {}
