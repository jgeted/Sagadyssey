package com.jgeted.sagadyssey.npc.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;

/** New skin palettes expose real hands beneath shorter clothing sleeves; no garment tint. */
final class NpcSkinSleeves {
    private NpcSkinSleeves() {}
    static void add(PartDefinition root,CubeDeformation cloth) {
        root.getChild("right_arm").addOrReplaceChild("skin_sleeve",CubeListBuilder.create().texOffs(40,16)
                .addBox(-2,-2,-2,3,10,4,cloth),PartPose.ZERO);
        root.getChild("left_arm").addOrReplaceChild("skin_sleeve",CubeListBuilder.create().texOffs(32,48)
                .addBox(-1,-2,-2,3,10,4,cloth),PartPose.ZERO);
    }
    static void configure(HumanoidModel<NpcRenderState> model,boolean exposeHands) {
        for(var arm:java.util.List.of(model.leftArm,model.rightArm)) {
            arm.skipDraw=exposeHands;
            arm.getChild("skin_sleeve").visible=exposeHands;
        }
    }
}
