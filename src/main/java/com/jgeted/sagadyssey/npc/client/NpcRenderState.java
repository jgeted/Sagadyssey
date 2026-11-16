package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcFaceVariant;
import com.jgeted.sagadyssey.npc.appearance.NpcGender;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.identity.NpcSocialStatus;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;

public final class NpcRenderState extends HumanoidRenderState {
    public NpcGender gender;
    public NpcFaceVariant face;
    public com.jgeted.sagadyssey.npc.appearance.NpcHeadShape headShape =
            com.jgeted.sagadyssey.npc.appearance.NpcHeadShape.CLASSIC;
    public com.jgeted.sagadyssey.npc.appearance.NpcHairStyle hairStyle =
            com.jgeted.sagadyssey.npc.appearance.NpcHairStyle.CLASSIC;
    public com.jgeted.sagadyssey.npc.appearance.NpcSkinTone skinTone =
            com.jgeted.sagadyssey.npc.appearance.NpcSkinTone.ORIGINAL;
    public NpcSocialStatus socialStatus;
    public NpcProfession profession;
    public NpcAction action;
    public float actionProgress;
    public boolean helmeted;
    public boolean resting;
    public boolean mounted;
    public boolean chestArmored;
    public boolean legArmored;
    public boolean militaryClothing;
    public boolean carryingStandard;
    public boolean standardInOffhand;
    public HumanoidArm standardArm() { return standardInOffhand ? mainArm.getOpposite() : mainArm; }
    public String clothingOutfit;
    public String clothingFaction;
    public String clothingPalette;
    public int logisticsStage;
    public String logisticsText;
}
