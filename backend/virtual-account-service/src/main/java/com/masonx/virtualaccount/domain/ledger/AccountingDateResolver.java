package com.masonx.virtualaccount.domain.ledger;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Single accounting-date policy for ledger posting.
 *
 * The resolver is deliberately pure code: no locks, no DB reads, and no shared
 * mutable state. It prevents posting rules from using host-local dates that can
 * vary by deployment timezone.
 */
@Component
public class AccountingDateResolver {

    static final ZoneId LEDGER_ZONE = ZoneOffset.UTC;

    public LocalDate today() {
        return fromInstant(Instant.now());
    }

    public LocalDate fromInstant(Instant instant) {
        Instant timestamp = instant != null ? instant : Instant.now();
        return timestamp.atZone(LEDGER_ZONE).toLocalDate();
    }
}
