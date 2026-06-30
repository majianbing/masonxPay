package com.masonx.railsim.iso8583;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import jakarta.annotation.PreDestroy;
import org.jpos.iso.packager.GenericPackager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Netty TCP server simulating the ISO 8583 card network (port 9091).
 *
 * <p>Each inbound connection gets a fresh {@link CardNetworkSimHandler} instance.
 * Business logic (issuer decision + latency simulation) runs on a dedicated
 * {@link DefaultEventExecutorGroup} so the IO thread is never blocked.
 */
@Component
public class Iso8583SimServer {

    private static final Logger log = LoggerFactory.getLogger(Iso8583SimServer.class);

    private final int             tcpPort;
    private final GenericPackager packager;
    private final IssuerSimulator issuerSim;
    private final VaIssuerClient  vaIssuer;
    private final int             minLatencyMs;
    private final int             maxLatencyMs;
    private final int             timeoutSilenceMs;

    private EventLoopGroup          bossGroup;
    private EventLoopGroup          workerGroup;
    private DefaultEventExecutorGroup businessGroup;
    private Channel                 serverChannel;

    public Iso8583SimServer(
            @Value("${railsim.tcp-port:9091}")              int tcpPort,
            @Value("${railsim.min-latency-ms:50}")          int minLatencyMs,
            @Value("${railsim.max-latency-ms:200}")         int maxLatencyMs,
            @Value("${railsim.timeout-silence-ms:35000}")   int timeoutSilenceMs,
            GenericPackager packager,
            IssuerSimulator issuerSim,
            VaIssuerClient vaIssuer) {
        this.tcpPort          = tcpPort;
        this.packager         = packager;
        this.issuerSim        = issuerSim;
        this.vaIssuer         = vaIssuer;
        this.minLatencyMs     = minLatencyMs;
        this.maxLatencyMs     = maxLatencyMs;
        this.timeoutSilenceMs = timeoutSilenceMs;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup     = new NioEventLoopGroup(1);
        workerGroup   = new NioEventLoopGroup();
        businessGroup = new DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors() * 2);

        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2))
                                .addLast(new SimFrameEncoder())
                                .addLast(businessGroup, new CardNetworkSimHandler(
                                        packager, issuerSim, vaIssuer,
                                        minLatencyMs, maxLatencyMs, timeoutSilenceMs,
                                        businessGroup));
                    }
                });

        serverChannel = b.bind(tcpPort).sync().channel();
        log.info("ISO8583 card-network simulator listening on TCP port {}", tcpPort);
    }

    @PreDestroy
    public void stop() {
        log.info("Shutting down ISO8583 simulator");
        if (serverChannel != null) serverChannel.close().awaitUninterruptibly();
        if (businessGroup != null) businessGroup.shutdownGracefully();
        if (workerGroup  != null) workerGroup.shutdownGracefully();
        if (bossGroup    != null) bossGroup.shutdownGracefully();
    }
}
