package com.masonx.virtualaccount.ops.dto;

import com.masonx.virtualaccount.domain.ledger.AccountingPeriod;

import java.time.LocalDate;

public record AccountingPeriodResponse(
        String accountingPeriodId,
        String merchantId,
        String mode,
        String asset,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status
) {
    public static AccountingPeriodResponse from(AccountingPeriod period) {
        return new AccountingPeriodResponse(
                period.accountingPeriodId(),
                period.merchantId(),
                period.mode().name(),
                period.asset(),
                period.periodStart(),
                period.periodEnd(),
                period.status().name());
    }
}
