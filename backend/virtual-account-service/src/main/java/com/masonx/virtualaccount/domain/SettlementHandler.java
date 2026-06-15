package com.masonx.virtualaccount.domain;

/**
 * Domain entry point for a settlement. The skeleton ships a logging stub; the
 * real implementation (double-entry posting into the VA ledger) is built as part
 * of the VA domain design.
 */
public interface SettlementHandler {
    void handle(RecordSettlementCommand command);
}
