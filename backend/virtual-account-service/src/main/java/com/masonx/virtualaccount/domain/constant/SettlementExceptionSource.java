package com.masonx.virtualaccount.domain.constant;

/**
 * Which consume path produced a parked settlement exception. Determines the
 * payload type stored and the handler the retry path re-drives.
 */
public enum SettlementExceptionSource {
    RAIL_SETTLEMENT,     // payload = RailSettlementEvent → CardRailSettlementHandler
    GATEWAY_SETTLEMENT,  // payload = RecordSettlementCommand → LedgerSettlementHandler
    UNKNOWN              // unrecognized payload parked by the Kafka backstop; not retryable
}
