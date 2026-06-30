package com.masonx.rail.web.dto;

import com.masonx.rail.canonical.RailPaymentStatus;

public record AuthorizeResponse(
        String railPaymentId,
        RailPaymentStatus status,
        String authCode,
        String responseCode,
        String networkRef,
        String failureReason
) {
}
