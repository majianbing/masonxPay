package com.masonx.rail.adapter;

import com.masonx.contracts.rail.PaymentRail;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.rail.canonical.CanonicalPaymentCommand;
import com.masonx.rail.canonical.PaymentRailAdapter;
import com.masonx.rail.canonical.RailPaymentStatus;
import com.masonx.rail.canonical.RailResponse;
import com.masonx.rail.iso8583.Iso8583LogService;
import com.masonx.rail.iso8583.Iso8583NettyClient;
import com.masonx.rail.iso8583.Iso8583NettyClient.Iso8583TimeoutException;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared ISO 8583 adapter logic for Visa and Mastercard simulator rails.
 *
 * <p>Subclasses provide {@link #networkName()} and {@link #acquirerId()}.
 * All field construction, transport, response parsing, and log writes live here.
 *
 * <p>DE2 (PAN) is masked via {@code CardToken.masked()} before any log write or DB insert.
 */
public abstract class AbstractIso8583Adapter implements PaymentRailAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractIso8583Adapter.class);

    private static final AtomicInteger STAN_SEQ = new AtomicInteger(0);

    private static final DateTimeFormatter TRANS_DT_FMT =
            DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_TIME_FMT =
            DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_DATE_FMT =
            DateTimeFormatter.ofPattern("MMdd").withZone(ZoneOffset.UTC);

    // ISO 4217 numeric currency codes for common test currencies.
    private static final Map<String, String> CURRENCY_CODES = Map.of(
            "USD", "840",
            "EUR", "978",
            "GBP", "826",
            "JPY", "392",
            "CAD", "124",
            "AUD", "036",
            "SGD", "702"
    );

    protected final GenericPackager   packager;
    protected final Iso8583NettyClient client;
    protected final Iso8583LogService  logService;

    protected AbstractIso8583Adapter(GenericPackager packager,
                                     Iso8583NettyClient client,
                                     Iso8583LogService logService) {
        this.packager   = packager;
        this.client     = client;
        this.logService = logService;
    }

    /** Network identifier matched against the "network" metadata key in the command. */
    protected abstract String networkName();

    /** Acquirer institution ID (DE32), unique per simulated network. */
    protected abstract String acquirerId();

    @Override
    public boolean supports(PaymentRail rail, String network) {
        return PaymentRail.CARD_ISO8583.equals(rail) && networkName().equals(network);
    }

    @Override
    public RailResponse execute(CanonicalPaymentCommand command) {
        if (command.type() != MoneyMovementType.CARD_AUTH) {
            throw new UnsupportedOperationException(
                    "ISO8583 simulator adapter currently supports CARD_AUTH only: " + command.type());
        }

        String stan = nextStan();
        String rrn  = generateRrn();
        Instant sentAt = Instant.now();

        ISOMsg request;
        try {
            request = buildAuthRequest(command, stan, rrn);
        } catch (ISOException e) {
            throw new RuntimeException("Failed to build ISO8583 0100 for paymentId=" + command.paymentId(), e);
        }

        String maskedPan = command.cardToken().masked();

        logService.logSend(command.paymentId(), networkName(), "0100", stan, rrn, maskedPan, null);

        String correlationKey = buildCorrelationKey(stan, rrn, sentAt);
        logService.persistCorrelation(command.paymentId(), "CARD_ISO8583",
                networkName(), correlationKey, stan, rrn);

        try {
            byte[] packed = request.pack();
            ISOMsg response = client.sendAndReceive(packed, stan);

            String responseMti  = safeGetMti(response);
            String responseCode = response.getString(39);
            String authCode     = response.getString(38);
            String responseStan = response.getString(11);

            logService.logReceive(command.paymentId(), networkName(), responseMti,
                    responseStan, rrn, maskedPan, responseCode);

            return toRailResponse(command.paymentId(), responseCode, authCode, correlationKey);

        } catch (Iso8583TimeoutException e) {
            log.warn("ISO8583 timeout paymentId={} STAN={}", command.paymentId(), stan);
            logService.logSend(command.paymentId(), networkName(), "TIMEOUT", stan, rrn, maskedPan, null);
            return new RailResponse(command.paymentId(), RailPaymentStatus.UNKNOWN,
                    null, null, correlationKey, "ISO8583 timeout — outcome unknown", Instant.now());

        } catch (ISOException e) {
            throw new RuntimeException("ISO8583 pack error for paymentId=" + command.paymentId(), e);
        }
    }

    @Override
    public RailResponse query(String railPaymentId) {
        throw new UnsupportedOperationException("ISO8583 status query not implemented — use reversal for UNKNOWN state");
    }

    @Override
    public RailResponse reverse(String railPaymentId, String reasonCode) {
        throw new UnsupportedOperationException("ISO8583 reversal (0400) is implemented in MR2");
    }

    // ── field construction ────────────────────────────────────────────────────

    private ISOMsg buildAuthRequest(CanonicalPaymentCommand command, String stan, String rrn)
            throws ISOException {
        ISOMsg msg = new ISOMsg();
        msg.setPackager(packager);
        msg.setMTI("0100");

        Instant now = Instant.now();

        msg.set(2,  command.cardToken().testPan());           // PAN (masked before any log write)
        msg.set(3,  "000000");                                // processing code: auth
        msg.set(4,  formatAmount(command.amount()));          // amount in minor units, 12 digits
        msg.set(7,  TRANS_DT_FMT.format(now));               // transmission date/time MMDDHHmmss
        msg.set(11, stan);                                    // STAN (6 digits)
        msg.set(12, LOCAL_TIME_FMT.format(now));              // local time HHmmss
        msg.set(13, LOCAL_DATE_FMT.format(now));              // local date MMDD
        msg.set(32, acquirerId());                            // acquiring institution ID
        msg.set(37, rrn);                                     // RRN (12 chars)
        msg.set(41, "TERM0001");                              // terminal ID
        msg.set(42, truncateMerchantId(command.merchantId()));// merchant ID (max 15 chars)
        msg.set(49, currencyCode(command.currency()));        // ISO 4217 numeric currency code

        return msg;
    }

    // ── response parsing ──────────────────────────────────────────────────────

    private RailResponse toRailResponse(String paymentId, String responseCode,
                                        String authCode, String networkRef) {
        if (responseCode == null) {
            return new RailResponse(paymentId, RailPaymentStatus.FAILED,
                    null, null, networkRef, "Missing DE39 response code", Instant.now());
        }
        return switch (responseCode) {
            case "00" -> new RailResponse(paymentId, RailPaymentStatus.APPROVED,
                    authCode, responseCode, networkRef, null, Instant.now());
            case "51" -> new RailResponse(paymentId, RailPaymentStatus.DECLINED,
                    null, responseCode, networkRef, "Insufficient funds", Instant.now());
            case "05" -> new RailResponse(paymentId, RailPaymentStatus.DECLINED,
                    null, responseCode, networkRef, "Do not honor", Instant.now());
            case "14" -> new RailResponse(paymentId, RailPaymentStatus.DECLINED,
                    null, responseCode, networkRef, "Invalid card number", Instant.now());
            case "91" -> new RailResponse(paymentId, RailPaymentStatus.DECLINED,
                    null, responseCode, networkRef, "Issuer unavailable", Instant.now());
            default   -> new RailResponse(paymentId, RailPaymentStatus.DECLINED,
                    null, responseCode, networkRef, "Declined: DE39=" + responseCode, Instant.now());
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String nextStan() {
        return String.format("%06d", STAN_SEQ.incrementAndGet() % 1_000_000);
    }

    private static String generateRrn() {
        return String.format("%012d", System.currentTimeMillis() % 1_000_000_000_000L);
    }

    private String buildCorrelationKey(String stan, String rrn, Instant sentAt) {
        ZonedDateTime zdt = sentAt.atZone(ZoneOffset.UTC);
        String txDate = String.format("%02d%02d", zdt.getMonthValue(), zdt.getDayOfMonth());
        return networkName() + ":" + acquirerId() + ":" + stan + ":" + rrn + ":" + txDate;
    }

    private static String formatAmount(BigDecimal amount) {
        long minor = amount.multiply(BigDecimal.valueOf(100)).longValue();
        return String.format("%012d", minor);
    }

    private static String currencyCode(String currency) {
        return CURRENCY_CODES.getOrDefault(
                currency != null ? currency.toUpperCase() : "USD", "840");
    }

    private static String truncateMerchantId(String merchantId) {
        if (merchantId == null) return "UNKNOWN        ";
        return merchantId.length() <= 15
                ? String.format("%-15s", merchantId)
                : merchantId.substring(0, 15);
    }

    private static String safeGetMti(ISOMsg msg) {
        try { return msg.getMTI(); } catch (ISOException e) { return "????"; }
    }
}
