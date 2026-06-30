package com.masonx.virtualaccount.account.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Admin request to create a TENANT account (WALLET, CASH, CREDIT_LINE, etc.) for a merchant.
 *
 * <p>{@code normalBalance}, {@code assetClass}, and {@code scale} are derived automatically
 * from {@code accountType} and {@code asset}. {@code mode} defaults to {@link Mode#TEST}.
 */
public record CreateAccountRequest(
        @NotBlank String merchantId,
        @NotBlank String orgId,
        @NotNull  AccountType accountType,
        @NotBlank String asset,
        Mode mode              // null → TEST
) {
}
