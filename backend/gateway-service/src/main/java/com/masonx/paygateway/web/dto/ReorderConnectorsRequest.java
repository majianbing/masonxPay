package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ReorderConnectorsRequest(
        @NotNull List<BrandOrder> items
) {
    public record BrandOrder(
            @NotNull String provider,
            int displayOrder
    ) {}
}
