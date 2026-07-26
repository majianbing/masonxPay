package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardProgramFundingModel;
import com.masonx.virtualaccount.domain.constant.CardProgramStatus;
import com.masonx.virtualaccount.domain.constant.CardProgramSystemOfRecord;

import java.time.Instant;

public record CardProgramResponse(
        String programId,
        String merchantId,
        Mode mode,
        String issuerPartnerId,
        String name,
        String currency,
        String binRangeMetadata,
        CardProgramStatus status,
        CardProgramSystemOfRecord systemOfRecord,
        CardProgramFundingModel fundingModel,
        String featureFlagsJson,
        String defaultControlsJson,
        String settlementModelJson,
        String feeScheduleId,
        String externalProgramId,
        String externalFundingSourceId,
        Instant createdAt,
        Instant updatedAt
) {
}
