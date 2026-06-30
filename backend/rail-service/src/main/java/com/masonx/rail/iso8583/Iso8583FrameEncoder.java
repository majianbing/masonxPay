package com.masonx.rail.iso8583;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Prepends a 2-byte big-endian length header to each outbound packed ISO 8583 message.
 *
 * Frame format: [2 bytes length][N bytes message body]
 *
 * The matching decoder is Netty's built-in {@code LengthFieldBasedFrameDecoder}
 * configured with lengthFieldLength=2, initialBytesToStrip=2.
 */
public class Iso8583FrameEncoder extends MessageToByteEncoder<byte[]> {

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] msg, ByteBuf out) {
        out.writeShort(msg.length);
        out.writeBytes(msg);
    }
}
