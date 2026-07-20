package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.AccountingPeriodStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AccountingPeriodService {

    private final AccountingPeriodRepository repo;
    private final SnowflakeIdGenerator idGen;

    public AccountingPeriodService(AccountingPeriodRepository repo, SnowflakeIdGenerator idGen) {
        this.repo = repo;
        this.idGen = idGen;
    }

    public void assertOpen(Mode mode, String asset, LocalDate effectiveDate) {
        repo.findPlatformPeriod(mode, asset, effectiveDate)
                .filter(period -> period.status() == AccountingPeriodStatus.CLOSED)
                .ifPresent(period -> {
                    throw new BusinessException("VA_ACCOUNTING_PERIOD_CLOSED",
                            "Accounting period is closed for mode=" + mode
                                    + " asset=" + asset
                                    + " effectiveDate=" + effectiveDate);
                });
    }

    public AccountingPeriod createOpenPlatformPeriod(Mode mode, String asset,
                                                     LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart.isAfter(periodEnd)) {
            throw new BusinessException("VA_ACCOUNTING_PERIOD_INVALID",
                    "periodStart must be on or before periodEnd");
        }
        String normalizedAsset = asset.toUpperCase();
        if (repo.existsOverlappingPlatformPeriod(mode, normalizedAsset, periodStart, periodEnd)) {
            throw new BusinessException("VA_ACCOUNTING_PERIOD_OVERLAP",
                    "Accounting period overlaps an existing period for mode=" + mode
                            + " asset=" + normalizedAsset);
        }
        AccountingPeriod period = new AccountingPeriod(
                idGen.generate(MasonXIdPrefix.ACCOUNTING_PERIOD.prefix()),
                AccountingPeriodRepository.PLATFORM_MERCHANT_ID,
                mode,
                normalizedAsset,
                periodStart,
                periodEnd,
                AccountingPeriodStatus.OPEN);
        repo.save(period);
        return period;
    }

    public AccountingPeriod closePeriod(String accountingPeriodId) {
        AccountingPeriod period = repo.findById(accountingPeriodId)
                .orElseThrow(() -> new BusinessException("VA_NOT_FOUND",
                        "Accounting period not found: " + accountingPeriodId, 404));
        if (period.status() == AccountingPeriodStatus.CLOSED) {
            return period;
        }
        repo.markClosed(accountingPeriodId);
        return repo.findById(accountingPeriodId)
                .orElseThrow(() -> new IllegalStateException(
                        "Accounting period vanished during close: " + accountingPeriodId));
    }

    public List<AccountingPeriod> findPage(AccountingPeriodStatus status, int page, int size) {
        return repo.findPage(status, page, size);
    }

    public long count(AccountingPeriodStatus status) {
        return repo.count(status);
    }
}
