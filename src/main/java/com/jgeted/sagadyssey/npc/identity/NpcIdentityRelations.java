package com.jgeted.sagadyssey.npc.identity;

import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;

/** 比较 NPC 与玩家的礼仪优先级，决定问候动作和称呼前缀。 */
public final class NpcIdentityRelations {
    private NpcIdentityRelations() { }

    public static int compareNpcToPlayer(NpcBase npc, ServerPlayer player) {
        int npcRank = NpcIdentity.precedence(npc.getSocialClass(), npc.getOffice());
        var current = CharacterRegistry.current(player);
        if (!player.isAlive() || current == null) return 0;
        int playerRank = current.precedence();
        return Integer.compare(npcRank, playerRank);
    }

    public static NpcAction greetingAction(NpcBase npc, ServerPlayer player) {
        int relation = compareNpcToPlayer(npc, player);
        if (relation < 0) return NpcAction.COURTESY_BOW;
        if (relation > 0) {
            return npc.getOffice() == NpcOffice.SOVEREIGN
                    ? NpcAction.ROYAL_WAVE : NpcAction.NOBLE_ACKNOWLEDGE;
        }
        return NpcIdentity.greetingAction(npc.getSocialClass(), npc.getOffice());
    }

    public static Component addressPrefix(NpcBase npc, ServerPlayer player) {
        int relation = compareNpcToPlayer(npc, player);
        if (relation < 0) {
            var identity = CharacterRegistry.current(player);
            if (identity == null) return Component.empty();
            return Component.translatable("dialogue.sagadyssey.relative.upward",
                    NpcIdentity.title(identity.socialClass(), identity.office(), identity.factionId()));
        }
        if (relation > 0) {
            return Component.translatable("dialogue.sagadyssey.relative.downward");
        }
        return Component.empty();
    }
}
