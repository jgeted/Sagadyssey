package com.jgeted.sagadyssey.npc.network;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.network.NetworkStrings;
import com.jgeted.sagadyssey.core.network.NetworkRateLimiter;
import com.jgeted.sagadyssey.npc.entity.FarmMode;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.service.AuthorityScope;
import com.jgeted.sagadyssey.social.service.ServiceRelationshipData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：设置农民 NPC 的务农模式。
 * 设置成功后同时命令 NPC 开始工作（WORK）。
 */
public record NpcFarmModePacket(int npcId, String modeName) implements CustomPacketPayload {

    public static final Type<NpcFarmModePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Sagadyssey.MOD_ID, "npc_farm_mode")
    );

    public static final StreamCodec<ByteBuf, NpcFarmModePacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeInt(packet.npcId);
                NetworkStrings.write(buf, packet.modeName, 32);
            },
            buf -> new NpcFarmModePacket(buf.readInt(), NetworkStrings.read(buf, 32))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final NpcFarmModePacket data, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (!NetworkRateLimiter.allow(player, "npc_farm_mode", 2)) return;
            Entity entity = player.level().getEntity(data.npcId);

            if (!(entity instanceof NpcBase npc)) {
                player.displayClientMessage(Component.literal("§c这个 NPC 已经不存在了"), true);
                return;
            }
            var principal = CharacterRegistry.currentId(player);
            if (!player.isAlive() || principal == null) {
                player.displayClientMessage(Component.literal("当前没有可用的在生人物，不能行使工作授权。"), true);
                return;
            }
            var verdict = ServiceRelationshipData.get(player.level().getServer()).authorize(
                    principal, npc.getCharacterId(),
                    AuthorityScope.WORK, false, player.level().getGameTime());
            if (!verdict.allowed()) {
                player.displayClientMessage(Component.literal("§c没有工作模式授权：" + verdict.reason()), true);
                return;
            }
            if (player.distanceToSqr(npc) > 64.0D) {
                player.displayClientMessage(Component.literal("§c你离 NPC 太远了"), true);
                return;
            }
            if (npc.getProfession() != NpcProfession.FARMER) {
                player.displayClientMessage(Component.literal("§e这个 NPC 不是农民"), true);
                return;
            }

            FarmMode mode;
            try {
                mode = FarmMode.valueOf(data.modeName);
            } catch (IllegalArgumentException e) {
                player.displayClientMessage(Component.literal("§c无效的务农模式"), true);
                return;
            }

            npc.setFarmMode(mode);
            var request = com.jgeted.sagadyssey.npc.brain.command.NpcCommandService.issue(player,npc,NpcCommand.WORK);
            if (!request.accepted()) {player.displayClientMessage(Component.literal(request.detail()),true);return;}
            player.displayClientMessage(Component.literal(
                    "§a" + npc.getNpcName() + " 已切换到「" + mode.getDisplayName() + "」模式并开始工作"), true);
            if (mode == FarmMode.CANE) {
                player.displayClientMessage(Component.literal(
                        "§e提示：甘蔗模式需要给 NPC 甘蔗和两桶水"), false);
            }
        });
    }
}
