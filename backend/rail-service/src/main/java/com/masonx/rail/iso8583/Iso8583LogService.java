package com.masonx.rail.iso8583;

import com.masonx.common.id.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists masked ISO 8583 message logs and network correlation records.
 *
 * <p>DE2 (PAN) is NEVER written in raw form. Callers must pass the already-masked
 * value (first 6 + **** + last 4), as produced by {@code CardToken.masked()}.
 */
@Service
public class Iso8583LogService {

    private static final Logger log = LoggerFactory.getLogger(Iso8583LogService.class);

    private final JdbcTemplate         jdbc;
    private final SnowflakeIdGenerator idGen;

    public Iso8583LogService(JdbcTemplate jdbc, SnowflakeIdGenerator idGen) {
        this.jdbc  = jdbc;
        this.idGen = idGen;
    }

    public void logSend(String paymentId, String network, String mti,
                        String stan, String rrn, String maskedDe2, String responseCode) {
        insertLog(paymentId, "SEND", network, mti, stan, rrn, maskedDe2, responseCode);
    }

    public void logReceive(String paymentId, String network, String mti,
                           String stan, String rrn, String maskedDe2, String responseCode) {
        insertLog(paymentId, "RECV", network, mti, stan, rrn, maskedDe2, responseCode);
    }

    private void insertLog(String paymentId, String direction, String network,
                           String mti, String stan, String rrn,
                           String maskedDe2, String responseCode) {
        try {
            String id = idGen.generate("iso_");
            jdbc.update("""
                    INSERT INTO rail_iso8583_log
                        (id, payment_id, direction, network, mti, stan, rrn, masked_de2, response_code)
                    VALUES (?, ?, ?::rail_direction, ?, ?, ?, ?, ?, ?)
                    """,
                    id, paymentId, direction, network, mti, stan, rrn, maskedDe2, responseCode);
        } catch (Exception e) {
            // Log failure must not break the payment flow.
            log.error("Failed to persist ISO8583 log for paymentId={} direction={}: {}",
                    paymentId, direction, e.getMessage(), e);
        }
    }

    /**
     * Persists a reconciliation exception into the ISO 8583 log.
     * Used for reversal exhaustion — payment requires manual intervention.
     *
     * <p>MTI is set to "REXH" (reversal exhausted) to distinguish from
     * normal message types. This never throws — log failure must not break the payment.
     */
    public void logReconException(String paymentId, String network, String exceptionType) {
        try {
            String mti = switch (exceptionType) {
                case "REVERSAL_EXHAUSTED" -> "REXH";
                default                   -> "RERR";
            };
            insertLog(paymentId, "RECV", network, mti, null, null, null, "99");
        } catch (Exception e) {
            log.error("Failed to log recon exception for paymentId={}: {}", paymentId, e.getMessage(), e);
        }
    }

    /**
     * Persists the composite correlation key linking the internal payment ID to
     * the network-assigned identifiers (STAN, RRN, transmission date).
     *
     * <p>Composite key format: {@code {network}:{acquirerId}:{stan}:{rrn}:{transmissionDate}}
     * STAN and RRN alone are NOT globally unique — only the composite is.
     */
    public void persistCorrelation(String paymentId, String rail, String network,
                                   String correlationKey, String stan, String rrn) {
        try {
            String id = idGen.generate("corr_");
            jdbc.update("""
                    INSERT INTO rail_network_correlation
                        (id, payment_id, rail, network, correlation_key, stan, rrn)
                    VALUES (?, ?, ?::rail_type, ?, ?, ?, ?)
                    ON CONFLICT (correlation_key) DO NOTHING
                    """,
                    id, paymentId, rail, network, correlationKey, stan, rrn);
        } catch (Exception e) {
            log.error("Failed to persist correlation for paymentId={}: {}", paymentId, e.getMessage(), e);
        }
    }
}
