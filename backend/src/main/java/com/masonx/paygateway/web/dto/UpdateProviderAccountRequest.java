package com.masonx.paygateway.web.dto;

public record UpdateProviderAccountRequest(
        String label,
        String status,          // ACTIVE | DISABLED
        Integer weight,         // optional; 1–100
        Integer fixedFeeCents,  // flat per-transaction fee in smallest currency unit (e.g. 30 = $0.30)
        Integer rateBps         // percentage rate in basis points (e.g. 290 = 2.90%)
) {}
