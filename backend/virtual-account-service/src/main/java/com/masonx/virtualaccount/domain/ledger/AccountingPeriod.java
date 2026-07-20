package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.AccountingPeriodStatus;

import java.time.LocalDate;

public record AccountingPeriod(
        String accountingPeriodId,
        String merchantId,
        Mode mode,
        String asset,
        LocalDate periodStart,
        LocalDate periodEnd,
        AccountingPeriodStatus status
) {
}
