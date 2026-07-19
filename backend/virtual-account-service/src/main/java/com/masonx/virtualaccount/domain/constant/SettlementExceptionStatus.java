package com.masonx.virtualaccount.domain.constant;

/**
 * Lifecycle of a parked settlement exception. OPEN rows are the ops worklist;
 * RESOLVED means a retry posted (or deduped) the event; DISCARDED requires an
 * explicit note. A redelivery of the same event reopens a DISCARDED row.
 */
public enum SettlementExceptionStatus {
    OPEN,
    RESOLVED,
    DISCARDED
}
