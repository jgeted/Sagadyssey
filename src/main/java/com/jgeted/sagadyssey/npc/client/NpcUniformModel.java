package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcMilitaryOutfit;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;

/** 可组合军用布衣及罩袍。原身体、脸和真实护甲仍各自由原模型负责。 */
public final class NpcUniformModel extends HumanoidModel<NpcRenderState> {
    private final NpcModel parent;
    private final ModelPart surcoat, leftSkirt, rightSkirt, hood;
    public NpcUniformModel(ModelPart root, NpcModel parent) {
        super(root);
        this.parent = parent;
        surcoat = body.getChild("surcoat");
        leftSkirt = root.getChild("livery_left_skirt"); rightSkirt = root.getChild("livery_right_skirt");
        hood = body.getChild("bow_hood_down");
        NpcSkinSleeves.configure(this,false);
    }
    public static LayerDefinition createBodyLayer() {
        var mesh = new MeshDefinition(); var root = mesh.getRoot();
        var head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        var cloth = new CubeDeformation(.34F);
        var body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16,16)
                .addBox(-4,0,-2,8,12,4,cloth), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40,16)
                .addBox(-2,-2,-2,3,12,4,cloth), PartPose.offset(-5,2,0));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(32,48)
                .addBox(-1,-2,-2,3,12,4,cloth), PartPose.offset(5,2,0));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0,16)
                .addBox(-2,0,-2,4,12,4,new CubeDeformation(.29F)), PartPose.offset(-1.9F,12,0));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(16,48)
                .addBox(-2,0,-2,4,12,4,new CubeDeformation(.29F)), PartPose.offset(1.9F,12,0));
        // 完整无袖罩袍：前后片、侧缝与肩桥连成衣服，而非胸前的一条围片。
        var surcoat = body.addOrReplaceChild("surcoat", CubeListBuilder.create(), PartPose.ZERO);
        surcoat.addOrReplaceChild("front", CubeListBuilder.create().texOffs(0,0)
                .addBox(-4.3F,0,-2.65F,8.6F,12.05F,.25F), PartPose.ZERO);
        surcoat.addOrReplaceChild("back", CubeListBuilder.create().texOffs(20,0)
                .addBox(-4.3F,0,2.40F,8.6F,12.05F,.25F), PartPose.ZERO);
        surcoat.addOrReplaceChild("sides", CubeListBuilder.create().texOffs(52,0)
                .addBox(-4.5F,3,-2.4F,.25F,9.05F,4.8F).texOffs(52,0)
                .addBox(4.25F,3,-2.4F,.25F,9.05F,4.8F), PartPose.ZERO);
        surcoat.addOrReplaceChild("shoulders", CubeListBuilder.create().texOffs(48,40)
                .addBox(-4.3F,-.10F,-2.65F,2.3F,.25F,5.3F).texOffs(48,40)
                .addBox(2,-.10F,-2.65F,2.3F,.25F,5.3F), PartPose.ZERO);
        // 腰带在罩袍外侧，不能被胸片遮掉而只在两侧剩下断段。
        surcoat.addOrReplaceChild("belt", CubeListBuilder.create().texOffs(32,32)
                .addBox(-4.42F,8.7F,-2.80F,8.84F,.80F,5.6F), PartPose.ZERO);
        for (String side : java.util.List.of("left", "right")) {
            boolean left = side.equals("left");
            float x = left ? -1.8F : -2.4F;
            root.addOrReplaceChild("livery_" + side + "_skirt", CubeListBuilder.create().texOffs(0,32)
                    .addBox(x,-.2F,-2.65F,4.2F,8.4F,.25F).texOffs(12,32)
                    .addBox(x,-.2F,2.4F,4.2F,8.4F,.25F).texOffs(0,44)
                    .addBox(left ? 2.15F : -2.4F,-.2F,-2.4F,.25F,8.4F,4.8F),
                    PartPose.offset(side.equals("left") ? 1.9F : -1.9F,12,0));
        }
        body.addOrReplaceChild("bow_hood_down", CubeListBuilder.create().texOffs(40,0)
                .addBox(-2.5F,.2F,2.7F,5,3.5F,1.1F), PartPose.ZERO);
        NpcSkinSleeves.add(root,cloth);
        return LayerDefinition.create(mesh,64,64);
    }
    void configure(NpcModel parent, NpcMilitaryOutfit outfit, boolean chestArmor, boolean headEquipped) {
        configure(parent, outfit, chestArmor, headEquipped, false);
    }
    void configure(NpcModel parent, NpcMilitaryOutfit outfit, boolean chestArmor, boolean headEquipped,
                   boolean legArmor) {
        parent.copyPoseTo(this);
        NpcSkinSleeves.configure(this,false);
        // Clothing stays beneath the armor shell. Its opaque pixels cover the cloth;
        // transparent pixels (including chainmail holes) reveal the same complete outfit.
        // Reset every child because one model is reused for armored and unarmored NPCs.
        surcoat.getAllParts().forEach(ModelPart::resetPose);
        if (legArmor) {
            // Vanilla leggings also render a thinner waist/body shell (inflation .5).
            surcoat.getChild("front").z = .20F;
            surcoat.getChild("back").z = -.20F;
            surcoat.getChild("sides").xScale = .985F;
            surcoat.getChild("shoulders").zScale = .92F;
            surcoat.getChild("belt").zScale = .82F;
        }
        NpcModel.copyTransform(leftLeg, leftSkirt); NpcModel.copyTransform(rightLeg, rightSkirt);
        leftSkirt.zScale *= legArmor ? .89F : 1;
        rightSkirt.zScale *= legArmor ? .89F : 1;
        leftSkirt.xScale *= legArmor ? .985F : 1;
        rightSkirt.xScale *= legArmor ? .985F : 1;
        float skirtLength = outfit.heavyCavalry() ? 1 : outfit.mounted() ? .60F : .83F;
        leftSkirt.yScale *= skirtLength; rightSkirt.yScale *= skirtLength;
        hood.visible = outfit.bow() && !headEquipped;
        hood.z = 0;
        hood.zScale = chestArmor ? .74F : 1;
    }
    @Override public void setupAnim(NpcRenderState state) {
        parent.setupAnim(state);
        var outfit = com.jgeted.sagadyssey.npc.appearance.NpcMilitaryOutfit.find(state.clothingOutfit);
        if (outfit.isPresent()) configure(parent, outfit.get(), state.chestArmored, state.helmeted, state.legArmored);
        else com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole.find(state.clothingOutfit)
                .ifPresent(role -> configureRole(parent, role, state.chestArmored, state.helmeted, state.legArmored));
        NpcSkinSleeves.configure(this,state.skinTone!=com.jgeted.sagadyssey.npc.appearance.NpcSkinTone.ORIGINAL);
    }

    void configureRole(NpcModel parent, com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole role,
                       boolean chestArmor, boolean headEquipped, boolean legArmor) {
        configure(parent, role.baseOutfit(), chestArmor, headEquipped, legArmor);
        // The existing connected garment is reused, with a role-specific hem rather than a new body.
        leftSkirt.yScale = parent.leftLeg.yScale * role.hemScale();
        rightSkirt.yScale = parent.rightLeg.yScale * role.hemScale();
        hood.visible = role.hood() && !headEquipped && !chestArmor;
    }

}
