package com.jgeted.sagadyssey.npc.identity;

import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** 玩家身份数据的持久化入口。 */
public final class PlayerIdentityAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "sagadyssey");

    public static final Supplier<AttachmentType<PlayerIdentityData>> PLAYER_IDENTITY =
            ATTACHMENT_TYPES.register("player_identity", () ->
                    AttachmentType.builder(PlayerIdentityData::new)
                            .serialize(PlayerIdentityData.CODEC)
                            .copyOnDeath()
                            .build());

    private PlayerIdentityAttachments() { }

    public static PlayerIdentityData get(Entity entity) {
        return entity.getData(PLAYER_IDENTITY.get());
    }
}
