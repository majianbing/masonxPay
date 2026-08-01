package com.masonx.paygateway.fee;

import java.util.List;

public record GatewayFeeAssessmentSnapshot(
        GatewayFeeAssessment assessment,
        List<GatewayFeeAssessmentLine> lines
) {
    public GatewayFeeAssessmentSnapshot {
        lines = List.copyOf(lines != null ? lines : List.of());
    }
}
