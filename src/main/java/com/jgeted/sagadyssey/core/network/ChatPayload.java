package com.jgeted.sagadyssey.core.network;

import com.jgeted.sagadyssey.Sagadyssey;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 从客户端发送到服务端的数据包。
 * 服务端收到后向玩家发送聊天消息。
 */
public record ChatPayload(String message) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ChatPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "chat_message"));

    public static final StreamCodec<ByteBuf, ChatPayload> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                NetworkStrings.write(buf, packet.message, 256);
            },
            buf -> new ChatPayload(NetworkStrings.read(buf, 256))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
