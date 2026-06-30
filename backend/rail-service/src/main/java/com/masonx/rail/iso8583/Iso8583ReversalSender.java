package com.masonx.rail.iso8583;

import com.masonx.rail.service.ReversalTask;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds and sends ISO 8583 0400 (reversal) messages for UNKNOWN card transactions.
 *
 * <h3>Key invariants</h3>
 * <ul>
 *   <li>The 0400 uses a <em>new</em> STAN (different from the timed-out 0100 STAN).
 *   <li>DE37 (RRN) is preserved from the original 0100 so the network can match it.
 *   <li>DE90 (original data elements) carries the original MTI, STAN, and transmission
 *       time in the 42-digit format required by the standard.
 *   <li>Reversal STANs start at 500 000 to keep them visually distinct from auth STANs.
 * </ul>
 */
@Component
public class Iso8583ReversalSender {

    private static final Logger log = LoggerFactory.getLogger(Iso8583ReversalSender.class);

    // Reversal STANs start at 500 000 to keep them visually distinct from auth STANs (1–499 999).
    private static final AtomicInteger REVERSAL_STAN_SEQ = new AtomicInteger(500_000);

    private static final DateTimeFormatter TRANS_DT_FMT =
            DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_TIME_FMT =
            DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_DATE_FMT =
            DateTimeFormatter.ofPattern("MMdd").withZone(ZoneOffset.UTC);

    private static final Map<String, String> CURRENCY_CODES = Map.of(
            "USD", "840", "EUR", "978", "GBP", "826",
            "JPY", "392", "CAD", "124", "AUD", "036", "SGD", "702");

    private static final Map<String, String> ACQUIRER_IDS = Map.of(
            "VISA_SIM", "999001",
            "MC_SIM",   "999002");

    private final GenericPackager    packager;
    private final Iso8583NettyClient client;

    public Iso8583ReversalSender(GenericPackager packager, Iso8583NettyClient client) {
        this.packager = packager;
        this.client   = client;
    }

    /**
     * Sends a 0400 reversal for {@code task} and awaits a 0410 response.
     *
     * <p>Marks the original STAN as reversed before sending so that any late 0110
     * arriving on the channel is tagged as LATE_RESPONSE_AFTER_REVERSAL.
     *
     * @return true if the network returned DE39=00 in the 0410
     */
    public boolean sendReversal(ReversalTask task) {
        String newStan    = nextReversalStan();
        String acquirerId = ACQUIRER_IDS.getOrDefault(task.network(), "000000");

        ISOMsg request;
        try {
            request = build0400(task, newStan, acquirerId, Instant.now());
        } catch (ISOException e) {
            log.error("Failed to build 0400 for paymentId={}: {}", task.paymentId(), e.getMessage(), e);
            return false;
        }

        log.info("Sending 0400 paymentId={} originalStan={} reversalStan={}",
                task.paymentId(), task.originalStan(), newStan);

        // Register original STAN as reversed before flush so an in-flight late 0110
        // is tagged correctly even if it arrives in the milliseconds between these two calls.
        if (task.originalStan() != null) {
            client.markAsReversed(task.originalStan());
        }

        byte[] packed;
        try {
            packed = request.pack();
        } catch (ISOException e) {
            log.error("Pack error building 0400 for paymentId={}: {}", task.paymentId(), e.getMessage(), e);
            return false;
        }

        try {
            ISOMsg response = client.sendAndReceive(packed, newStan);
            String responseCode = response.getString(39);
            boolean accepted = "00".equals(responseCode);
            log.info("0410 received paymentId={} DE39={} accepted={}", task.paymentId(), responseCode, accepted);
            return accepted;

        } catch (Iso8583NettyClient.Iso8583TimeoutException e) {
            log.warn("0410 timeout paymentId={} originalStan={} reversalStan={}",
                    task.paymentId(), task.originalStan(), newStan);
            return false;
        } catch (Exception e) {
            log.error("Error awaiting 0410 for paymentId={}: {}", task.paymentId(), e.getMessage(), e);
            return false;
        }
    }

    private ISOMsg build0400(ReversalTask task, String newStan, String acquirerId, Instant now)
            throws ISOException {
        ISOMsg msg = new ISOMsg();
        msg.setPackager(packager);
        msg.setMTI("0400");

        msg.set(3,  "400000");                             // processing code: reversal
        msg.set(4,  formatAmount(task.amount()));          // same amount as original 0100
        msg.set(7,  TRANS_DT_FMT.format(now));            // new transmission date/time (now)
        msg.set(11, newStan);                              // NEW STAN for this reversal message
        msg.set(12, LOCAL_TIME_FMT.format(now));
        msg.set(13, LOCAL_DATE_FMT.format(now));
        msg.set(32, acquirerId);                           // acquirer institution ID
        msg.set(37, task.originalRrn());                  // original RRN preserved (network match)
        msg.set(41, "TERM0001");
        msg.set(42, truncate(task.merchantId(), 15));
        msg.set(49, currencyCode(task.currency()));
        msg.set(90, buildDe90(task, acquirerId));          // 42-digit original data elements
        return msg;
    }

    /**
     * Builds DE90 (42 numeric digits):
     * {@code original_mti(4) | original_stan(6) | tx_datetime(10) | acq_id(11) | fwd_id(11)}
     */
    private static String buildDe90(ReversalTask task, String acquirerId) {
        Instant txTime = task.originalTxTime() != null ? task.originalTxTime() : Instant.EPOCH;
        String txDt    = TRANS_DT_FMT.format(txTime);
        long   acqLong = parseLong(acquirerId);
        String stan    = task.originalStan() != null
                ? String.format("%06d", parseLong(task.originalStan()))
                : "000000";
        return "0100"
                + stan
                + txDt
                + String.format("%011d", acqLong)
                + "00000000000"; // forwarding institution not used
    }

    private static String nextReversalStan() {
        return String.format("%06d", REVERSAL_STAN_SEQ.incrementAndGet() % 1_000_000);
    }

    private static String formatAmount(BigDecimal amount) {
        long minor = amount.multiply(BigDecimal.valueOf(100)).longValue();
        return String.format("%012d", minor);
    }

    private static String currencyCode(String currency) {
        return CURRENCY_CODES.getOrDefault(
                currency != null ? currency.toUpperCase() : "USD", "840");
    }

    private static String truncate(String s, int max) {
        if (s == null) return " ".repeat(max);
        return s.length() <= max ? String.format("%-" + max + "s", s) : s.substring(0, max);
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0L; }
    }
}
