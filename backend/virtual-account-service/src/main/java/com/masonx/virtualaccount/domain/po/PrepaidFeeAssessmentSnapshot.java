package com.masonx.virtualaccount.domain.po;

import java.util.List;

public record PrepaidFeeAssessmentSnapshot(
        PrepaidFeeAssessment assessment,
        List<PrepaidFeeAssessmentLine> lines
) {
    public PrepaidFeeAssessmentSnapshot {
        lines = List.copyOf(lines != null ? lines : List.of());
    }
}
