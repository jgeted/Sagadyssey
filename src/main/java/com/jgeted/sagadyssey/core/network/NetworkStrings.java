package com.jgeted.sagadyssey.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

import java.nio.charset.StandardCharsets;

/** 有长度上限的 UTF-8 编解码，避免恶意客户端用长度字段申请巨量内存。 */
public final class NetworkStrings {
    private NetworkStrings() { }

    public static void write(ByteBuf buffer, String value, int maxBytes) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("Network string too long");
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
    }

    public static String read(ByteBuf buffer, int maxBytes) {
        int length = buffer.readInt();
        if (length < 0 || length > maxBytes || length > buffer.readableBytes()) {
            throw new DecoderException("Invalid network string length: " + length);
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
