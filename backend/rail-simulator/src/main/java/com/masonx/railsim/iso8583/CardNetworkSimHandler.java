package com.masonx.railsim.iso8583;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Netty server-side handler: simulates the card network processing an ISO 8583 authorization.
 *
 * <p>Routing by BIN prefix:
 * <ul>
 *   <li>4xxx → Visa simulator rules (IssuerSimulator, PAN suffix table)
 *   <li>5xxx → Mastercard simulator rules (same PAN suffix table)
 *   <li>999999 → VA issuer HTTP call (virtual-account-service)
 * </ul>
 *
 * <p>A new handler instance is created per channel connection. The executor offloads
 * business logic (including simulated latency sleeps) off the Netty IO thread.
 */
public class CardNetworkSimHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(CardNetworkSimHandler.class);

    private static final String VA_BIN_PREFIX = "999999";

    private final GenericPackager  packager;
    private final IssuerSimulator  issuerSim;
    private final VaIssuerClient   vaIssuer;
    private final int              minLatencyMs;
    private final int              maxLatencyMs;
    private final int              timeoutSilenceMs;
    private final Executor         executor;

    public CardNetworkSimHandler(GenericPackager packager,
                                  IssuerSimulator issuerSim,
                                  VaIssuerClient vaIssuer,
                                  int minLatencyMs,
                                  int maxLatencyMs,
                                  int timeoutSilenceMs,
                                  Executor executor) {
        this.packager         = packager;
        this.issuerSim        = issuerSim;
        this.vaIssuer         = vaIssuer;
        this.minLatencyMs     = minLatencyMs;
        this.maxLatencyMs     = maxLatencyMs;
        this.timeoutSilenceMs = timeoutSilenceMs;
        this.executor         = executor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
        byte[] bytes = new byte[frame.readableBytes()];
        frame.readBytes(bytes);
        // Hand off to executor so IO thread is not blocked by simulated latency.
        executor.execute(() -> processRequest(ctx, bytes));
    }

    private void processRequest(ChannelHandlerContext ctx, byte[] bytes) {
        // Read MTI from raw bytes before attempting full unpack.
        // 0400 (reversal) includes DE90 in the secondary bitmap; jPOS's ISOBasePackager
        // does not consume the secondary bitmap bytes during unpack, causing a field-offset
        // mismatch on DE32. Handle 0400 directly from raw bytes to avoid this.
        if (bytes.length < 4) {
            log.warn("Simulator received frame too short to contain MTI ({} bytes)", bytes.length);
            return;
        }
        String mti = new String(bytes, 0, 4, StandardCharsets.US_ASCII);

        if ("0400".equals(mti)) {
            handleReversalDirect(ctx, bytes);
            return;
        }

        ISOMsg request = new ISOMsg();
        request.setPackager(packager);
        try {
            request.unpack(bytes);
        } catch (ISOException e) {
            log.error("Simulator failed to unpack ISO8583 request: {}", e.getMessage(), e);
            return;
        }

        String pan  = request.getString(2);
        String stan = request.getString(11);
        log.info("Simulator received MTI={} STAN={} maskedPan={}", mti, stan, maskPan(pan));

        if ("0800".equals(mti)) {
            sendNetworkManagementResponse(ctx, request);
            return;
        }

        if (!"0100".equals(mti)) {
            log.warn("Simulator received unsupported MTI={} — ignoring", mti);
            return;
        }

        handleAuth(ctx, request, pan, stan);
    }

    /**
     * Handles a 0400 reversal by extracting the STAN directly from the raw bytes.
     *
     * <p>Our 0400 always has DE90 (original data elements) in the secondary bitmap.
     * jPOS's ISOBasePackager does not skip the secondary bitmap bytes during unpack,
     * so full unpack fails. Instead we read the STAN at its fixed offset:
     *
     * <pre>
     *   Offset  0: MTI         (4 bytes, ASCII)
     *   Offset  4: Primary bitmap (8 bytes, IFB_BITMAP binary)
     *   Offset 12: Secondary bitmap (8 bytes, IFB_BITMAP binary) — always present in our 0400
     *   Offset 20: DE3 processing code (6 bytes, IFA_NUMERIC)
     *   Offset 26: DE4 amount         (12 bytes, IFA_NUMERIC)
     *   Offset 38: DE7 tx datetime    (10 bytes, IFA_NUMERIC)
     *   Offset 48: DE11 STAN          (6 bytes, IFA_NUMERIC)  ← extracted here
     * </pre>
     */
    private void handleReversalDirect(ChannelHandlerContext ctx, byte[] bytes) {
        if (bytes.length < 54) {
            log.error("Simulator received 0400 too short to contain STAN (len={})", bytes.length);
            return;
        }
        String stan = new String(bytes, 48, 6, StandardCharsets.US_ASCII).trim();
        log.info("Simulator received MTI=0400 STAN={}", stan);
        simulateDelay(minLatencyMs, maxLatencyMs);
        try {
            ISOMsg response = new ISOMsg();
            response.setPackager(packager);
            response.setMTI("0410");
            response.set(11, stan);
            response.set(39, "00");
            byte[] packed = response.pack();
            ctx.writeAndFlush(packed);
            log.info("Simulator sent 0410 DE39=00 STAN={}", stan);
        } catch (ISOException e) {
            log.error("Simulator failed to pack 0410 for reversal STAN={}: {}", stan, e.getMessage(), e);
        }
    }

    private void handleAuth(ChannelHandlerContext ctx, ISOMsg request, String pan, String stan) {
        if (pan != null && pan.startsWith(VA_BIN_PREFIX)) {
            handleVaIssuerAuth(ctx, request, pan, stan);
        } else {
            handleSimulatorAuth(ctx, request, pan, stan);
        }
    }

    private void handleSimulatorAuth(ChannelHandlerContext ctx, ISOMsg request,
                                     String pan, String stan) {
        IssuerSimulator.Scenario scenario = issuerSim.detectScenario(pan);

        switch (scenario) {
            case TIMEOUT -> {
                log.info("Simulator TIMEOUT scenario for STAN={} — no response will be sent", stan);
                simulateDelay(timeoutSilenceMs);
                // No response — client times out and transitions to UNKNOWN.
            }
            case LATE_RESPONSE -> {
                log.info("Simulator LATE_RESPONSE scenario for STAN={} — responding after {}ms", stan, timeoutSilenceMs);
                simulateDelay(timeoutSilenceMs);
                sendAuthResponse(ctx, request, "00", issuerSim.generateAuthCode());
            }
            case DUPLICATE_RESPONSE -> {
                log.info("Simulator DUPLICATE_RESPONSE scenario for STAN={}", stan);
                simulateDelay(minLatencyMs, maxLatencyMs);
                String code = issuerSim.generateAuthCode();
                sendAuthResponse(ctx, request, "00", code);
                sendAuthResponse(ctx, request, "00", code);
            }
            default -> {
                simulateDelay(minLatencyMs, maxLatencyMs);
                String responseCode = issuerSim.responseCode(scenario);
                String authCode     = "00".equals(responseCode) ? issuerSim.generateAuthCode() : null;
                sendAuthResponse(ctx, request, responseCode, authCode);
            }
        }
    }

    private void handleVaIssuerAuth(ChannelHandlerContext ctx, ISOMsg request,
                                     String pan, String stan) {
        simulateDelay(minLatencyMs, maxLatencyMs);

        BigDecimal amount   = parseAmount(request.getString(4));
        String     currency = numericToIsoAlpha(request.getString(49));
        String     rrn      = request.getString(37);

        SimIssuerAuthResponse resp = vaIssuer.authorize(maskPan(pan), amount, currency, stan, rrn);
        log.info("VA issuer decision={} responseCode={} for STAN={}", resp.decision(), resp.responseCode(), stan);

        sendAuthResponse(ctx, request, resp.responseCode(),
                "APPROVED".equals(resp.decision()) ? resp.authCode() : null);
    }

    // ── response building ─────────────────────────────────────────────────────

    private void sendAuthResponse(ChannelHandlerContext ctx, ISOMsg request,
                                   String responseCode, String authCode) {
        try {
            ISOMsg response = new ISOMsg();
            response.setPackager(packager);
            response.setMTI("0110");

            copyField(response, request, 2);   // PAN
            copyField(response, request, 3);   // processing code
            copyField(response, request, 4);   // amount
            copyField(response, request, 7);   // transmission date/time
            copyField(response, request, 11);  // STAN — critical for correlation
            copyField(response, request, 12);  // local time
            copyField(response, request, 13);  // local date
            copyField(response, request, 32);  // acquirer ID
            copyField(response, request, 37);  // RRN
            if (authCode != null) {
                response.set(38, String.format("%-6s", authCode).substring(0, 6));
            }
            response.set(39, responseCode);
            copyField(response, request, 41);  // terminal ID
            copyField(response, request, 42);  // merchant ID
            copyField(response, request, 49);  // currency

            byte[] packed = response.pack();
            ctx.writeAndFlush(packed);

        } catch (ISOException e) {
            log.error("Simulator failed to pack 0110 response for STAN={}: {}", request.getString(11), e.getMessage(), e);
        }
    }

    private void sendNetworkManagementResponse(ChannelHandlerContext ctx, ISOMsg request) {
        try {
            ISOMsg response = new ISOMsg();
            response.setPackager(packager);
            response.setMTI("0810");
            copyField(response, request, 7);
            copyField(response, request, 11);
            copyField(response, request, 12);
            copyField(response, request, 13);
            response.set(39, "00");
            ctx.writeAndFlush(response.pack());
        } catch (ISOException e) {
            log.error("Simulator failed to pack 0810: {}", e.getMessage(), e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void simulateDelay(int fixedMs) {
        try { Thread.sleep(fixedMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void simulateDelay(int minMs, int maxMs) {
        int delay = minMs == maxMs ? minMs
                : minMs + ThreadLocalRandom.current().nextInt(maxMs - minMs + 1);
        try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void copyField(ISOMsg to, ISOMsg from, int fieldNo) {
        if (from.hasField(fieldNo)) {
            try {
                to.set(fieldNo, from.getString(fieldNo));
            } catch (Exception e) {
                // skip field if copy fails (ISOException or runtime variant depending on jPOS version)
            }
        }
    }

    private static BigDecimal parseAmount(String de4) {
        if (de4 == null || de4.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(de4.trim()).divide(BigDecimal.valueOf(100));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** Maps ISO 4217 numeric to alpha code for the VA service call. */
    private static String numericToIsoAlpha(String numeric) {
        if (numeric == null) return "USD";
        return switch (numeric.trim()) {
            case "840" -> "USD";
            case "978" -> "EUR";
            case "826" -> "GBP";
            case "392" -> "JPY";
            case "124" -> "CAD";
            case "036" -> "AUD";
            default    -> "USD";
        };
    }

    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 10) return "****";
        return pan.substring(0, 6) + "****" + pan.substring(pan.length() - 4);
    }

    private static String safeGetMti(ISOMsg msg) {
        try { return msg.getMTI(); } catch (ISOException e) { return "????"; }
    }
}
