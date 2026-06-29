package com.masonx.rail.iso8583;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PreDestroy;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Netty TCP client for the ISO 8583 card rail.
 *
 * <p>Maintains a single persistent channel to the rail-simulator ISO 8583 server (port 9091).
 * Channel is created lazily on first use and re-created on disconnect.
 *
 * <h3>Timeout discipline (MR2)</h3>
 * Two complementary mechanisms detect that a response will not arrive:
 * <ol>
 *   <li>{@code IdleStateHandler} in the Netty pipeline fires {@code READER_IDLE} when no
 *       bytes have been read within {@code readTimeoutMs}. The {@link Iso8583ClientHandler}
 *       handles this event by failing all pending futures with {@link Iso8583TimeoutException}.
 *   <li>{@code CompletableFuture.get(readTimeoutMs + 5 s)} is a safety-net fallback in case
 *       the {@code IdleStateHandler} fires slightly late.
 * </ol>
 * Either path throws {@link Iso8583TimeoutException} — callers must transition to UNKNOWN,
 * not FAILED, and a reversal task must be created.
 *
 * <h3>Late and duplicate response tracking (MR2)</h3>
 * {@link #markAsReversed(String)} registers a STAN as reversed so that the
 * {@link Iso8583ClientHandler} can distinguish a late 0110 (LATE_RESPONSE_AFTER_REVERSAL)
 * from a duplicate 0110 (silently ignored).
 */
@Component
public class Iso8583NettyClient {

    private static final Logger log = LoggerFactory.getLogger(Iso8583NettyClient.class);

    private final String host;
    private final int    tcpPort;
    private final int    connectTimeoutMs;
    private final int    readTimeoutMs;
    private final GenericPackager packager;

    private final ConcurrentHashMap<String, CompletableFuture<ISOMsg>> pending =
            new ConcurrentHashMap<>();

    /** STANs for which a 0400 reversal has been sent — shared with the channel handler. */
    private final Set<String> reversedStans  = ConcurrentHashMap.newKeySet();
    /** STANs whose futures completed normally — used to silence duplicate 0110s. */
    private final Set<String> completedStans = ConcurrentHashMap.newKeySet();

    private final EventLoopGroup workerGroup = new NioEventLoopGroup(2);
    private final Object         connectLock = new Object();
    private volatile Channel     channel;

    public Iso8583NettyClient(
            @Value("${rail.simulator.host:localhost}")              String host,
            @Value("${rail.simulator.tcp-port:9091}")               int    tcpPort,
            @Value("${rail.simulator.connect-timeout-ms:5000}")     int    connectTimeoutMs,
            @Value("${rail.simulator.read-timeout-ms:30000}")       int    readTimeoutMs,
            GenericPackager packager) {
        this.host             = host;
        this.tcpPort          = tcpPort;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs    = readTimeoutMs;
        this.packager         = packager;
    }

    /**
     * Sends {@code packedBytes} over the ISO 8583 channel and blocks until a correlated
     * response for {@code stan} (DE11) arrives.
     *
     * <p>The {@code IdleStateHandler} in the pipeline fires first (at {@code readTimeoutMs})
     * and fails the future with {@link Iso8583TimeoutException}. The {@code get()} call
     * then throws {@code ExecutionException} wrapping that exception. A 5-second buffer
     * is added to the {@code get()} deadline so the IdleState path always fires first.
     *
     * @throws Iso8583TimeoutException if no response arrives within the read-timeout window
     */
    public ISOMsg sendAndReceive(byte[] packedBytes, String stan) {
        CompletableFuture<ISOMsg> future = new CompletableFuture<>();
        pending.put(stan, future);

        try {
            Channel ch = acquireChannel();
            ch.writeAndFlush(packedBytes).addListener(f -> {
                if (!f.isSuccess()) {
                    pending.remove(stan);
                    future.completeExceptionally(
                            new RuntimeException("ISO8583 write failed: " + f.cause().getMessage(), f.cause()));
                }
            });

            // IdleStateHandler fires at readTimeoutMs and fails the future via the handler.
            // The +5000ms buffer ensures the ExecutionException path (not TimeoutException) fires.
            return future.get(readTimeoutMs + 5000L, TimeUnit.MILLISECONDS);

        } catch (ExecutionException e) {
            pending.remove(stan);
            if (e.getCause() instanceof Iso8583TimeoutException te) throw te;
            throw new RuntimeException("ISO8583 error for STAN=" + stan + ": " + e.getMessage(), e);

        } catch (TimeoutException e) {
            // Safety net: IdleStateHandler should have fired first, but protect against edge cases.
            pending.remove(stan);
            throw new Iso8583TimeoutException(stan, readTimeoutMs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(stan);
            throw new RuntimeException("ISO8583 send interrupted for STAN=" + stan, e);

        } catch (Exception e) {
            pending.remove(stan);
            throw new RuntimeException("ISO8583 send/receive failed for STAN=" + stan + ": " + e.getMessage(), e);
        }
    }

    /**
     * Marks {@code originalStan} as reversed so that any late 0110 arriving for this STAN
     * is logged as LATE_RESPONSE_AFTER_REVERSAL instead of being silently ignored or processed.
     *
     * <p>Call this before sending the 0400 so that even a very fast late 0110 is correctly tagged.
     */
    public void markAsReversed(String originalStan) {
        if (originalStan != null) {
            reversedStans.add(originalStan);
        }
    }

    private Channel acquireChannel() throws InterruptedException {
        if (channel != null && channel.isActive()) return channel;
        synchronized (connectLock) {
            if (channel != null && channel.isActive()) return channel;
            log.info("Connecting ISO8583 client to {}:{}", host, tcpPort);
            channel = buildBootstrap().connect(host, tcpPort).sync().channel();
            log.info("ISO8583 client connected to {}:{}", host, tcpPort);
            return channel;
        }
    }

    private Bootstrap buildBootstrap() {
        return new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                // Strip 2-byte length prefix, expose raw ISO 8583 frame bytes.
                                .addLast(new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2))
                                // Fire READER_IDLE when no bytes received within readTimeoutMs.
                                .addLast(new IdleStateHandler(readTimeoutMs, 0, 0,
                                        TimeUnit.MILLISECONDS))
                                .addLast(new Iso8583FrameEncoder())
                                .addLast(new Iso8583ClientHandler(packager, pending,
                                        reversedStans, completedStans, readTimeoutMs));
                    }
                });
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ISO8583 Netty client");
        if (channel != null) {
            channel.close().awaitUninterruptibly();
        }
        workerGroup.shutdownGracefully();
    }

    /** Thrown when the ISO 8583 simulator does not respond within the configured window. */
    public static final class Iso8583TimeoutException extends RuntimeException {
        private final String stan;
        private final int    timeoutMs;

        public Iso8583TimeoutException(String stan, int timeoutMs) {
            super("ISO8583 timeout: no response for STAN=" + stan + " within " + timeoutMs + " ms");
            this.stan      = stan;
            this.timeoutMs = timeoutMs;
        }

        public String getStan()     { return stan; }
        public int    getTimeoutMs(){ return timeoutMs; }
    }
}
