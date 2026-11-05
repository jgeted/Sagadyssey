package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkRateLimiter;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.handover.HandoverDecision;
import com.jgeted.sagadyssey.npc.handover.HandoverIntent;
import com.jgeted.sagadyssey.npc.handover.NpcHandoverService;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Explicit intent path used only when automatic world-space handover is ambiguous. */
public record NpcHandoverIntentPacket(UUID transferId, int npcId, InteractionHand hand,
                                      HandoverIntent intent, boolean wholeStack)
        implements CustomPacketPayload {
    public static final Type<NpcHandoverIntentPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_handover_intent"));
    public static final StreamCodec<ByteBuf, NpcHandoverIntentPacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeLong(packet.transferId.getMostSignificantBits());
                buffer.writeLong(packet.transferId.getLeastSignificantBits());
                buffer.writeInt(packet.npcId);
                buffer.writeByte(packet.hand.ordinal());
                buffer.writeByte(packet.intent.ordinal());
                buffer.writeBoolean(packet.wholeStack);
            }, buffer -> {
                UUID id = new UUID(buffer.readLong(), buffer.readLong());
                int npcId = buffer.readInt();
                int hand = buffer.readUnsignedByte();
                int intent = buffer.readUnsignedByte();
                if (hand >= InteractionHand.values().length || intent >= HandoverIntent.values().length) {
                    throw new DecoderException("invalid handover intent");
                }
                return new NpcHandoverIntentPacket(id, npcId, InteractionHand.values()[hand],
                        HandoverIntent.values()[intent], buffer.readBoolean());
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(NpcHandoverIntentPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !NetworkRateLimiter.allow(player, "npc_handover", 2)) return;
            if (!(player.serverLevel().getEntity(packet.npcId) instanceof NpcBase npc)) return;
            HandoverDecision decision = NpcHandoverService.offer(player, npc, packet.hand,
                    packet.wholeStack, packet.intent, packet.transferId);
            String color = decision.accepted() ? "§a"
                    : decision.status() == HandoverDecision.Status.NEEDS_CLARIFICATION ? "§e" : "§c";
            player.displayClientMessage(Component.literal(color + decision.reason()), true);
        });
    }
}
