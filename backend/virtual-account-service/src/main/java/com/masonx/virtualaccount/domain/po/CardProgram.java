package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardProgramFundingModel;
import com.masonx.virtualaccount.domain.constant.CardProgramStatus;
import com.masonx.virtualaccount.domain.constant.CardProgramSystemOfRecord;

import java.time.Instant;

/**
 * Merchant-facing prepaid card product configuration. The program points to an
 * issuer partner but keeps MasonXPay account mapping and controls explicit.
 */
public record CardProgram(
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
