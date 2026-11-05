package com.jgeted.sagadyssey.npc.identity;

import com.jgeted.sagadyssey.npc.appearance.NpcGender;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import net.minecraft.network.chat.Component;

/** 社会阶层和职务的组合规则。 */
public final class NpcIdentity {
    private NpcIdentity() { }

    public static int precedence(NpcSocialClass socialClass, NpcOffice office) {
        return Math.max(socialClass.precedence(), office.precedence());
    }

    public static boolean isDirectlyRecruitable(NpcSocialClass socialClass, NpcOffice office) {
        return socialClass.isDirectlyRecruitable() && !office.blocksDirectRecruitment();
    }

    public static int recruitmentCost(int baseCost, NpcSocialClass socialClass, NpcOffice office) {
        if (!isDirectlyRecruitable(socialClass, office)) return 0;
        double officeMultiplier = office == NpcOffice.SWORN ? 1.75D : 1.00D;
        return Math.max(1, (int) Math.ceil(baseCost * socialClass.recruitmentMultiplier() * officeMultiplier));
    }

    public static Component title(NpcSocialClass socialClass, NpcOffice office, String factionId) {
        return office == NpcOffice.NONE ? socialClass.displayName() : office.displayName(factionId);
    }

    /** NPC 称谓随人物性别变化；玩家身份仍可继续使用上方的中性重载。 */
    public static Component title(NpcSocialClass socialClass, NpcOffice office, String factionId,
                                  NpcGender gender) {
        if (office == NpcOffice.NONE || gender != NpcGender.FEMALE) {
            return title(socialClass, office, factionId);
        }
        return Component.translatable("office.sagadyssey." + NpcOffice.factionKey(factionId)
                + "." + office.id() + ".female");
    }

    /** 将组合身份投影到已有的七档服装和语言风格。 */
    public static NpcSocialStatus presentation(NpcSocialClass socialClass, NpcOffice office) {
        return switch (office) {
            case SOVEREIGN -> NpcSocialStatus.KING;
            case HIGH_RULER -> NpcSocialStatus.DUKE;
            case LOCAL_RULER -> NpcSocialStatus.LORD;
            case SWORN -> NpcSocialStatus.KNIGHT;
            case NONE -> switch (socialClass) {
                case COMMONER -> NpcSocialStatus.COMMONER;
                case FREEMAN -> NpcSocialStatus.FREEMAN;
                case BURGHER -> NpcSocialStatus.BURGHER;
                case NOBLE -> NpcSocialStatus.LORD;
                case ROYAL -> NpcSocialStatus.DUKE;
            };
        };
    }

    public static NpcAction greetingAction(NpcSocialClass socialClass, NpcOffice office) {
        return presentation(socialClass, office).greetingAction();
    }
}
