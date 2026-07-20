package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.constant.Direction;

import java.math.BigDecimal;

/**
 * One leg of a double-entry transaction before seq/signature are computed.
 * LedgerPostingService resolves drafts into immutable LedgerEntry rows.
 */
public record AccountingEntryDraft(
        String ledgerAccountId,
        Direction direction,
        BigDecimal amount,
        String asset,
        String sourceEventId,

        /*
         * Stable posting-rule-owned leg discriminator within sourceEventId.
         *
         * Most business events create one leg per account, so the default value is
         * enough. When a rule legitimately touches the same account more than once
         * for one event, it must assign deterministic semantic names such as
         * "principal", "fee", or "tax". Do not use a generated id here: this
         * field is part of the DB idempotency key
         * (ledger_account_id, source_event_id, source_event_leg).
         */
        String sourceEventLeg
) {
    public static final String DEFAULT_SOURCE_EVENT_LEG = "default";

    public AccountingEntryDraft(String ledgerAccountId,
                                Direction direction,
                                BigDecimal amount,
                                String asset,
                                String sourceEventId) {
        this(ledgerAccountId, direction, amount, asset, sourceEventId, DEFAULT_SOURCE_EVENT_LEG);
    }

    public AccountingEntryDraft {
        if (sourceEventLeg == null || sourceEventLeg.isBlank()) {
            sourceEventLeg = DEFAULT_SOURCE_EVENT_LEG;
        }
    }
}
