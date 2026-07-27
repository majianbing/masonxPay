package com.masonx.feeengine;

public interface FeeEngine {
    FeeAssessment assess(FeeScheduleVersion schedule, FeeContextSchema schema, FeeContext context);
}
