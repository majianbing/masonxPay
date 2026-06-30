package com.masonx.rail.adapter;

import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.rail.canonical.CanonicalPaymentCommand;
import com.masonx.rail.canonical.CardToken;
import com.masonx.rail.canonical.RailPaymentStatus;
import com.masonx.rail.canonical.RailResponse;
import com.masonx.rail.iso8583.Iso8583FrameEncoder;
import com.masonx.rail.iso8583.Iso8583LogService;
import com.masonx.rail.iso8583.Iso8583NettyClient;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: wires a real Iso8583NettyClient against an in-process echo server.
 * Tests the full Netty + jPOS + STAN-correlation stack without Docker.
 *
 * <p>The echo server mimics the simulator: reads 0100, mirrors DE11 (STAN), and sends
 * back a 0110 with a configurable DE39 response code.
 *
 * @tag integration
 */
@Tag("integration")
class Iso8583AuthIntegrationTest {

    private static final int TEST_PORT = 19091;

    private static GenericPackager    packager;
    private static EventLoopGroup     serverBoss;
    private static EventLoopGroup     serverWorker;
    private static Channel            serverChannel;
    private static Iso8583NettyClient client;

    @BeforeAll
    static void startServer() throws Exception {
        packager = new GenericPackager(
                Iso8583AuthIntegrationTest.class.getClassLoader()
                        .getResourceAsStream("iso8583-packager.xml"));

        serverBoss   = new NioEventLoopGroup(1);
        serverWorker = new NioEventLoopGroup();

        serverChannel = new ServerBootstrap()
                .group(serverBoss, serverWorker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LengthFieldBasedFrameDecoder(65535, 0, 2, 0, 2))
                                .addLast(new Iso8583FrameEncoder())
                                .addLast(new EchoAuthHandler(packager));
                    }
                })
                .bind(TEST_PORT).sync().channel();

        Iso8583LogService logService = Mockito.mock(Iso8583LogService.class);
        client = new Iso8583NettyClient(
                "localhost", TEST_PORT, 3000, 5000, packager);
    }

    @AfterAll
    static void stopServer() {
        client.shutdown();
        serverChannel.close().awaitUninterruptibly();
        serverWorker.shutdownGracefully();
        serverBoss.shutdownGracefully();
    }

    @Test
    void panSuffix0000_approved() {
        RailResponse resp = executeAuth("4111111111110000", "00");
        assertThat(resp.status()).isEqualTo(RailPaymentStatus.APPROVED);
        assertThat(resp.responseCode()).isEqualTo("00");
        assertThat(resp.authCode()).isNotBlank();
    }

    @Test
    void panSuffix0001_declinedInsufficientFunds() {
        RailResponse resp = executeAuth("4111111111110001", "51");
        assertThat(resp.status()).isEqualTo(RailPaymentStatus.DECLINED);
        assertThat(resp.responseCode()).isEqualTo("51");
        assertThat(resp.authCode()).isNull();
    }

    @Test
    void panSuffix0006_declinedInvalidCard() {
        RailResponse resp = executeAuth("4111111111110006", "14");
        assertThat(resp.status()).isEqualTo(RailPaymentStatus.DECLINED);
        assertThat(resp.responseCode()).isEqualTo("14");
    }

    // ── helper to drive the adapter directly ─────────────────────────────────

    private RailResponse executeAuth(String testPan, String expectedResponseCode) {
        // The echo server's DE39 is derived from the PAN suffix — set by EchoAuthHandler.
        CanonicalPaymentCommand command = new CanonicalPaymentCommand(
                "rp_test_" + testPan,
                "merchant_001",
                "TEST",
                "idem_" + testPan,
                PaymentRail.CARD_ISO8583,
                MoneyMovementType.CARD_AUTH,
                new BigDecimal("50.00"),
                "USD",
                new CardToken(testPan, "1225", "VISA_SIM"),
                null, null, null,
                Map.of("network", "VISA_SIM"));

        Iso8583LogService logService = Mockito.mock(Iso8583LogService.class);
        VisaSimIso8583Adapter adapter = new VisaSimIso8583Adapter(packager, client, logService);
        return adapter.execute(command);
    }

    // ── in-process echo server handler ───────────────────────────────────────

    private static class EchoAuthHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final GenericPackager packager;

        EchoAuthHandler(GenericPackager packager) { this.packager = packager; }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);

            try {
                ISOMsg request = new ISOMsg();
                request.setPackager(packager);
                request.unpack(bytes);

                String pan  = request.getString(2);
                String stan = request.getString(11);
                String suffix = pan != null && pan.length() >= 4
                        ? pan.substring(pan.length() - 4) : "0000";

                String responseCode = switch (suffix) {
                    case "0001" -> "51";
                    case "0002" -> "05";
                    case "0006" -> "14";
                    case "0007" -> "91";
                    default     -> "00";
                };

                ISOMsg response = new ISOMsg();
                response.setPackager(packager);
                response.setMTI("0110");
                response.set(11, stan);         // mirror STAN — correlation key
                response.set(39, responseCode);
                if ("00".equals(responseCode)) {
                    response.set(38, "AUTH01");
                }

                ctx.writeAndFlush(response.pack());

            } catch (ISOException e) {
                // test failure — propagate nothing, let client time out
            }
        }
    }
}
