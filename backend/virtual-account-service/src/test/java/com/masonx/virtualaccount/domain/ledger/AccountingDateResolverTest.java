package com.masonx.virtualaccount.domain.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingDateResolverTest {

    private final AccountingDateResolver resolver = new AccountingDateResolver();

    @Test
    void fromInstant_uses_utc_ledger_date() {
        assertThat(resolver.fromInstant(Instant.parse("2026-01-01T00:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }
}
