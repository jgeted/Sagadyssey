package com.jgeted.sagadyssey.social.character;

import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.npc.identity.PlayerIdentityAttachments;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/** 角色注册表的便捷服务入口。 */
public final class CharacterRegistry {
    private CharacterRegistry() {}

    public static CharacterRecord ensureCurrent(ServerPlayer player) {
        return CharacterRegistryData.get(player.level().getServer())
                .ensureCurrent(player, PlayerIdentityAttachments.get(player));
    }

    @Nullable
    public static CharacterRecord current(ServerPlayer player) {
        return CharacterRegistryData.get(player.level().getServer()).current(player.getUUID());
    }

    @Nullable
    public static UUID currentId(ServerPlayer player) {
        CharacterRecord record = current(player);
        return record == null ? null : record.id();
    }

    public static boolean isCurrentCharacter(ServerPlayer player, UUID characterId) {
        UUID current = currentId(player);
        return current != null && current.equals(characterId);
    }

    public static UUID legacyOwnerCharacter(ServerPlayer player) {
        ensureCurrent(player);
        return CharacterRegistryData.get(player.level().getServer()).legacyOwnerCharacter(player.getUUID());
    }

    public static void updateIdentity(ServerPlayer player, NpcSocialClass socialClass,
                                      NpcOffice office, String factionId) {
        CharacterRegistryData.get(player.level().getServer())
                .updateIdentity(player.getUUID(), socialClass, office, factionId);
    }
}
