package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.gui.NpcTradeScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端→客户端：交易完成后回传新经验/等级，驱动 GUI 刷新。
 */
public record NpcTradeResponsePacket(int npcId, int newExperience, int newLevel) implements CustomPacketPayload {

    public static final Type<NpcTradeResponsePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_trade_response")
    );

    public static final StreamCodec<ByteBuf, NpcTradeResponsePacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeInt(p.npcId); buf.writeInt(p.newExperience); buf.writeInt(p.newLevel); },
            buf -> new NpcTradeResponsePacket(buf.readInt(), buf.readInt(), buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(final NpcTradeResponsePacket data, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof NpcTradeScreen screen) {
                screen.onExperienceUpdated(data.newExperience, data.newLevel);
            }
        });
    }
}
