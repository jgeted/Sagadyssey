package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * NPC 交互数据包。客户端点击菜单按钮时发给服务端。
 * action 字符串标识点击了什么按钮：recruit / change_profession / trade
 */
public record NpcInteractionPacket(int npcId, String action) implements CustomPacketPayload {

    public static final Type<NpcInteractionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_interact")
    );

    public static final StreamCodec<ByteBuf, NpcInteractionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeInt(packet.npcId);
                byte[] bytes = packet.action.getBytes();
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
            },
            buf -> {
                int npcId = buf.readInt();
                int len = buf.readInt();
                byte[] bytes = new byte[len];
                buf.readBytes(bytes);
                return new NpcInteractionPacket(npcId, new String(bytes));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final NpcInteractionPacket data, final IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            player.displayClientMessage(
                    Component.literal("NPC 交互：" + data.action + "（功能开发中）"), false);
        });
    }
}
