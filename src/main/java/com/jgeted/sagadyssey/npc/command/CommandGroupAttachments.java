package com.jgeted.sagadyssey.npc.command;

import com.jgeted.sagadyssey.npc.command.network.CommandGroupSyncPayload;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** 玩家集团数据的注册和同步入口。 */
public final class CommandGroupAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "sagadyssey");

    public static final Supplier<AttachmentType<PlayerCommandData>> COMMAND_GROUP =
            ATTACHMENT_TYPES.register("command_group", () ->
                    AttachmentType.builder(PlayerCommandData::new)
                            .serialize(com.mojang.serialization.MapCodec.assumeMapUnsafe(PlayerCommandData.CODEC))
                            .copyOnDeath()
                            .build());

    private CommandGroupAttachments() {}

    public static PlayerCommandData get(Entity entity) {
        return entity.getData(COMMAND_GROUP.get());
    }

    public static void registerNpc(ServerPlayer player, NpcBase npc) {
        get(player).registerMember(npc.getCharacterId(), npc.getNpcName());
    }

    public static void unregisterNpc(ServerPlayer player, NpcBase npc) {
        get(player).unregisterMember(npc.getCharacterId());
    }

    public static void sync(ServerPlayer player, boolean openScreen) {
        // 旧存档迁移：第一次打开指挥杖时自动收录附近已招募 NPC。
        PlayerCommandData data = get(player);
        if (openScreen && data.memberSquadsView().isEmpty()) {
            player.level().getEntitiesOfClass(NpcBase.class,
                            player.getBoundingBox().inflate(128.0D),
                            npc -> npc.isAlive() && npc.isOwnedBy(player.getUUID()))
                    .forEach(npc -> registerNpc(player, npc));
        }
        PacketDistributor.sendToPlayer(player, CommandGroupSyncPayload.from(player, openScreen));
    }
}
