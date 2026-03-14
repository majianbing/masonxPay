package com.masonx.paygateway.web.dto;

public record UpdateProviderAccountRequest(
        String label,
        String status     // ACTIVE | DISABLED
) {}
