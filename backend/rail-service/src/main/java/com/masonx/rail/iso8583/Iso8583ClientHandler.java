package com.masonx.rail.iso8583;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty inbound handler for the ISO 8583 client channel.
 *
 * <h3>Response correlation</h3>
 * Correlates inbound responses (0110, 0210, 0410, 0810) with pending requests
 * using DE11 (STAN) as the key. A {@link CompletableFuture} is registered per
 * outbound request and completed when the matching response arrives.
 *
 * <h3>MR2 — late and duplicate response discipline</h3>
 * <ul>
 *   <li><b>Late response after reversal</b>: a 0110 for a STAN already in
 *       {@code reversedStans} is logged as LATE_RESPONSE_AFTER_REVERSAL and
 *       discarded. Payment status is NOT changed.
 *   <li><b>Duplicate response</b>: a second 0110 for a STAN in {@code completedStans}
 *       is silently ignored at DEBUG level.
 *   <li><b>IdleStateEvent</b>: when {@code IdleStateHandler} fires (no channel read
 *       within the configured window), pending futures are failed with
 *       {@link Iso8583NettyClient.Iso8583TimeoutException} so callers go to UNKNOWN.
 * </ul>
 */
public class Iso8583ClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(Iso8583ClientHandler.class);

    private final GenericPackager                                    packager;
    private final ConcurrentHashMap<String, CompletableFuture<ISOMsg>> pending;
    private final Set<String>                                        reversedStans;
    private final Set<String>                                        completedStans;
    private final int                                                readTimeoutMs;

    public Iso8583ClientHandler(GenericPackager packager,
                                ConcurrentHashMap<String, CompletableFuture<ISOMsg>> pending,
                                Set<String> reversedStans,
                                Set<String> completedStans,
                                int readTimeoutMs) {
        this.packager       = packager;
        this.pending        = pending;
        this.reversedStans  = reversedStans;
        this.completedStans = completedStans;
        this.readTimeoutMs  = readTimeoutMs;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
        byte[] bytes = new byte[frame.readableBytes()];
        frame.readBytes(bytes);

        ISOMsg response = new ISOMsg();
        response.setPackager(packager);
        try {
            response.unpack(bytes);
        } catch (ISOException e) {
            log.error("Failed to unpack ISO8583 response frame: {}", e.getMessage(), e);
            return;
        }

        String stan = response.getString(11);
        String mti  = safeGetMti(response);
        if (stan == null) {
            log.warn("Received ISO8583 {} with no DE11 (STAN) — cannot correlate", mti);
            return;
        }

        CompletableFuture<ISOMsg> future = pending.remove(stan);
        if (future != null) {
            completedStans.add(stan);
            future.complete(response);

        } else if (reversedStans.contains(stan)) {
            // A 0110 arrived after we already sent a 0400 for this STAN.
            // Payment status must NOT be changed — this is a reconciliation exception.
            log.warn("[LATE_RESPONSE_AFTER_REVERSAL] received {} for STAN={} already reversed — discarding",
                    mti, stan);

        } else if (completedStans.contains(stan)) {
            // Second 0110 for the same STAN (e.g. simulator PAN suffix 0005 duplicate scenario).
            log.debug("Duplicate ISO8583 {} for completed STAN={} — ignoring", mti, stan);

        } else {
            log.warn("Received ISO8583 {} for unknown STAN={} — possible race with timeout", mti, stan);
        }
    }

    /**
     * Handles {@link IdleStateEvent}: fires when no data has been read within the
     * configured window. Fails any pending futures so callers transition to UNKNOWN.
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent ide && ide.state() == IdleState.READER_IDLE) {
            if (!pending.isEmpty()) {
                log.warn("ISO8583 channel READER_IDLE — timing out {} pending STAN(s)", pending.size());
                pending.forEach((stan, future) ->
                        future.completeExceptionally(
                                new Iso8583NettyClient.Iso8583TimeoutException(stan, readTimeoutMs)));
                pending.clear();
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("ISO8583 channel exception: {}", cause.getMessage(), cause);
        failAllPending(cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("ISO8583 channel disconnected — failing {} pending request(s)", pending.size());
        failAllPending(new RuntimeException("ISO8583 channel disconnected"));
    }

    private void failAllPending(Throwable cause) {
        pending.forEach((stan, future) -> future.completeExceptionally(cause));
        pending.clear();
    }

    private static String safeGetMti(ISOMsg msg) {
        try { return msg.getMTI(); } catch (ISOException e) { return "UNKNOWN_MTI"; }
    }
}
