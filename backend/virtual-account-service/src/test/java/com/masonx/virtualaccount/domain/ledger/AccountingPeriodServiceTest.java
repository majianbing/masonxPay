package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.AccountingPeriodStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingPeriodServiceTest {

    @Mock AccountingPeriodRepository repo;

    @Test
    void assertOpen_allows_date_without_configured_period() {
        var service = new AccountingPeriodService(repo, new SnowflakeIdGenerator(0));
        LocalDate date = LocalDate.of(2026, 1, 15);
        when(repo.findPlatformPeriod(Mode.TEST, "USD", date)).thenReturn(Optional.empty());

        service.assertOpen(Mode.TEST, "USD", date);
    }

    @Test
    void assertOpen_rejects_closed_period() {
        var service = new AccountingPeriodService(repo, new SnowflakeIdGenerator(0));
        LocalDate date = LocalDate.of(2026, 1, 15);
        when(repo.findPlatformPeriod(Mode.TEST, "USD", date)).thenReturn(Optional.of(
                new AccountingPeriod("ap_1", AccountingPeriodRepository.PLATFORM_MERCHANT_ID,
                        Mode.TEST, "USD", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                        AccountingPeriodStatus.CLOSED)));

        assertThatThrownBy(() -> service.assertOpen(Mode.TEST, "USD", date))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code())
                        .isEqualTo("VA_ACCOUNTING_PERIOD_CLOSED"));
    }
}
