package com.masonx.railsim.iso8583;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** Prepends a 2-byte big-endian length header to each outbound ISO 8583 response frame. */
public class SimFrameEncoder extends MessageToByteEncoder<byte[]> {

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] msg, ByteBuf out) {
        out.writeShort(msg.length);
        out.writeBytes(msg);
    }
}
