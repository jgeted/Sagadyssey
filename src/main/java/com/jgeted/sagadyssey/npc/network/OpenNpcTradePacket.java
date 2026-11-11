package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkRateLimiter;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.faction.FactionAttachments;
import com.jgeted.sagadyssey.npc.trade.NpcTradeAccessPolicy;
import com.jgeted.sagadyssey.npc.trade.TradeSession;
import com.jgeted.sagadyssey.npc.trade.TradeSessionData;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import com.jgeted.sagadyssey.worldsim.rights.AccessPolicy;
import com.jgeted.sagadyssey.worldsim.rights.RightsStateData;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record OpenNpcTradePacket(int npcEntityId) implements CustomPacketPayload {
    public static final Type<OpenNpcTradePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "open_trade_v2"));
    public static final StreamCodec<ByteBuf, OpenNpcTradePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeInt(value.npcEntityId),
            buffer -> new OpenNpcTradePacket(buffer.readInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenNpcTradePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !NetworkRateLimiter.allow(player, "open_trade_v2", 4)
                    || !(player.level().getEntity(packet.npcEntityId) instanceof NpcBase npc)
                    || player.distanceToSqr(npc) > 36.0D) return;
            UUID playerId = CharacterRegistry.currentId(player);
            if (!player.isAlive() || playerId == null) {
                player.sendSystemMessage(Component.literal("当前没有可用的在生人物，不能打开交易。"));
                return;
            }
            UUID npcId = npc.getCharacterId();
            long now = player.level().getGameTime();
            boolean directServiceParty = ServiceRelationshipData.get(player.level().getServer())
                    .hasActiveBetween(playerId, npcId, now);
            NpcTradeAccessPolicy.Decision access = NpcTradeAccessPolicy.evaluate(
                    FactionAttachments.getStandings(player), npc.getFaction(), directServiceParty);
            if (!access.allowed()) {
                player.displayClientMessage(Component.literal("§c" + access.explanation()), true);
                return;
            }
            UUID playerContainer = ContainerIds.playerPersonal(playerId);
            UUID npcContainer = ContainerIds.npcPersonal(npcId);
            RightsStateData rights = RightsStateData.get(player.level().getServer());
            rights.ensureContext(playerContainer, "player:" + playerId + ":personal",
                    SubjectRef.character(playerId), SubjectRef.character(playerId), AccessPolicy.PRIVATE);
            rights.ensureContext(npcContainer, "npc:" + npcId + ":personal",
                    SubjectRef.character(npcId), SubjectRef.character(npcId), AccessPolicy.PRIVATE);
            TradeSession session = TradeSessionData.get(player.level().getServer()).open(playerId, npcId,
                    playerContainer, npcContainer, now);
            PacketDistributor.sendToPlayer(player, TradeSessionPayload.create(player, npc, session));
        });
    }
}
