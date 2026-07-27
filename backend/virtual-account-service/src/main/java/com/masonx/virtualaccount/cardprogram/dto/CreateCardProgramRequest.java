package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardProgramFundingModel;
import com.masonx.virtualaccount.domain.constant.CardProgramStatus;
import com.masonx.virtualaccount.domain.constant.CardProgramSystemOfRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCardProgramRequest(
        @NotBlank String merchantId,
        Mode mode,
        @NotBlank String issuerPartnerId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{2,20}$") String currency,
        String binRangeMetadata,
        CardProgramStatus status,
        @NotNull CardProgramSystemOfRecord systemOfRecord,
        @NotNull CardProgramFundingModel fundingModel,
        String featureFlagsJson,
        String defaultControlsJson,
        String settlementModelJson,
        @Size(max = 64) String feeScheduleId,
        @Size(max = 100) String externalProgramId,
        @Size(max = 100) String externalFundingSourceId
) {
}
