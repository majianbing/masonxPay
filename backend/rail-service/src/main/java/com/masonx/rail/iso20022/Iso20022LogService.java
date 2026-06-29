package com.masonx.rail.iso20022;

import com.masonx.common.id.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists ISO 20022 message metadata to {@code rail_iso20022_log} and
 * the four-ID correlation chain to {@code rail_network_correlation}.
 *
 * <p>Raw XML is never stored. Only structured identifiers and status codes.
 */
@Service
public class Iso20022LogService {

    private static final Logger log = LoggerFactory.getLogger(Iso20022LogService.class);

    private final JdbcTemplate         jdbc;
    private final SnowflakeIdGenerator idGen;

    public Iso20022LogService(JdbcTemplate jdbc, SnowflakeIdGenerator idGen) {
        this.jdbc  = jdbc;
        this.idGen = idGen;
    }

    public void logSend(String paymentId, String network, Iso20022MessageType type,
                        String messageId, String instructionId, String endToEndId) {
        insertLog(paymentId, "SEND", network, type, messageId, instructionId, endToEndId, null, null);
    }

    public void logReceive(String paymentId, String network, Iso20022ParsedMessage msg) {
        insertLog(paymentId, "RECV", network, msg.type(),
                msg.messageId(), msg.instructionId(), msg.endToEndId(),
                msg.transactionId(), msg.statusCode());
    }

    /**
     * Persists all four ISO 20022 correlation IDs to {@code rail_network_correlation}.
     * Uses ON CONFLICT DO NOTHING so duplicate log calls are idempotent.
     */
    public void persistCorrelation(String paymentId, String network,
                                   String messageId, String endToEndId) {
        try {
            String correlationKey = network + ":iso20022:" + endToEndId;
            jdbc.update("""
                    INSERT INTO rail_network_correlation
                        (id, payment_id, rail, network, correlation_key,
                         iso20022_message_id, iso20022_end_to_end_id)
                    VALUES (?, ?, 'BANK_ISO20022'::rail_type, ?, ?, ?, ?)
                    ON CONFLICT (correlation_key) DO NOTHING
                    """,
                    idGen.generate("corr_"), paymentId, network,
                    correlationKey, messageId, endToEndId);
        } catch (Exception e) {
            log.error("Failed to persist ISO20022 correlation paymentId={}: {}", paymentId, e.getMessage(), e);
        }
    }

    private void insertLog(String paymentId, String direction, String network,
                           Iso20022MessageType type, String messageId, String instructionId,
                           String endToEndId, String transactionId, String statusCode) {
        try {
            jdbc.update("""
                    INSERT INTO rail_iso20022_log
                        (id, payment_id, direction, network, message_name,
                         message_id, instruction_id, end_to_end_id, transaction_id, status_code)
                    VALUES (?, ?, ?::rail_direction, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    idGen.generate("i20_"), paymentId,
                    direction, network, messageName(type),
                    messageId, instructionId, endToEndId, transactionId, statusCode);
        } catch (Exception e) {
            log.error("Failed to persist ISO20022 log paymentId={} direction={}: {}",
                    paymentId, direction, e.getMessage(), e);
        }
    }

    private static String messageName(Iso20022MessageType type) {
        return switch (type) {
            case PAIN_001 -> "pain.001";
            case PAIN_002 -> "pain.002";
            case PACS_002 -> "pacs.002";
            case PACS_004 -> "pacs.004";
            case CAMT_054 -> "camt.054";
        };
    }
}
