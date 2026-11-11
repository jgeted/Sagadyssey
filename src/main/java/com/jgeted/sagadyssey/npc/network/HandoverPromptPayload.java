package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

public record HandoverPromptPayload(int npcId, InteractionHand hand, String npcName,
                                    String itemName, int availableCount) implements CustomPacketPayload {
    public static final Type<HandoverPromptPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "handover_prompt"));
    public static final StreamCodec<ByteBuf, HandoverPromptPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeInt(packet.npcId);
                buffer.writeByte(packet.hand.ordinal());
                NetworkStrings.write(buffer, packet.npcName, 128);
                NetworkStrings.write(buffer, packet.itemName, 256);
                buffer.writeInt(packet.availableCount);
            }, buffer -> {
                int npcId = buffer.readInt();
                int hand = buffer.readUnsignedByte();
                if (hand >= InteractionHand.values().length) throw new DecoderException("invalid hand");
                return new HandoverPromptPayload(npcId, InteractionHand.values()[hand],
                        NetworkStrings.read(buffer, 128), NetworkStrings.read(buffer, 256),
                        Math.max(1, buffer.readInt()));
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
