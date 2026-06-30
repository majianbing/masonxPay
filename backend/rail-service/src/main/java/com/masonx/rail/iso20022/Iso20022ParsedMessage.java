package com.masonx.rail.iso20022;

import java.math.BigDecimal;

/**
 * Protocol-independent representation of a parsed ISO 20022 message.
 *
 * <p>All four IDs in the correlation chain are carried here so callers can
 * persist them to {@code rail_iso20022_log} and {@code rail_network_correlation}
 * without re-parsing the XML.
 */
public record Iso20022ParsedMessage(
        Iso20022MessageType type,
        String messageId,        // GrpHdr/MsgId
        String endToEndId,       // PmtId/EndToEndId or OrgnlEndToEndId
        String instructionId,    // PmtId/InstrId (pain.001 only)
        String transactionId,    // TxId (pacs.002 onwards)
        String statusCode,       // TxSts (ACCP/RJCT/ACSC/ACSP) or null
        String reasonCode,       // StsRsnInf/Rsn/Cd or RtrRsnInf/Rsn/Cd
        BigDecimal amount,       // returned amount (camt.054, pacs.004)
        String currency
) {
    public boolean isAccepted() {
        return "ACCP".equals(statusCode) || "ACSC".equals(statusCode) || "ACSP".equals(statusCode);
    }

    public boolean isRejected() {
        return "RJCT".equals(statusCode);
    }

    public boolean isSettled() {
        return "ACSC".equals(statusCode);
    }
}
