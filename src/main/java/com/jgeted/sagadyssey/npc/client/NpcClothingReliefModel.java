package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcMilitaryOutfit;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.identity.NpcSocialStatus;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;

/** Incremental, closed-volume fastenings; no painted armor, weapons, or replacement body. */
public final class NpcClothingReliefModel extends HumanoidModel<NpcRenderState> {
    private final NpcModel parent;
    private final ModelPart root, fittings;
    public NpcClothingReliefModel(ModelPart root, NpcModel parent) {
        super(root);
        this.parent = parent; this.root = root; fittings = body.getChild("fittings");
    }

    public static LayerDefinition createBodyLayer() {
        var mesh = new MeshDefinition(); var root = mesh.getRoot();
        var head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        var body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        var fittings = body.addOrReplaceChild("fittings", CubeListBuilder.create(), PartPose.ZERO);
        fittings.addOrReplaceChild("waist_belt", CubeListBuilder.create().texOffs(0,0)
                .addBox(-4.47F,8.55F,-2.97F,8.94F,1.05F,5.94F), PartPose.ZERO);
        // Four separate metal bars leave a real hole through the buckle frame.
        fittings.addOrReplaceChild("buckle", CubeListBuilder.create().texOffs(0,32)
                .addBox(-.58F,8.63F,-3.13F,1.16F,.16F,.26F).texOffs(0,32)
                .addBox(-.58F,9.36F,-3.13F,1.16F,.16F,.26F).texOffs(0,32)
                .addBox(-.58F,8.79F,-3.13F,.16F,.57F,.26F).texOffs(0,32)
                .addBox(.42F,8.79F,-3.13F,.16F,.57F,.26F).texOffs(0,32)
                .addBox(-.38F,9.02F,-3.10F,.76F,.11F,.11F), PartPose.ZERO);
        fittings.addOrReplaceChild("belt_tail", CubeListBuilder.create().texOffs(0,0)
                .addBox(.18F,9.55F,-2.94F,.55F,1.85F,.18F), PartPose.ZERO);
        fittings.addOrReplaceChild("belt_loops", CubeListBuilder.create().texOffs(0,0)
                .addBox(-2.9F,8.43F,-2.94F,.36F,1.35F,.17F).texOffs(0,0)
                .addBox(2.5F,8.43F,-2.94F,.36F,1.35F,.17F), PartPose.ZERO);
        fittings.addOrReplaceChild("neck_fold", CubeListBuilder.create().texOffs(32,0)
                .addBox(-2.35F,.08F,-2.94F,1.45F,.55F,.22F).texOffs(32,0)
                .addBox(.9F,.08F,-2.94F,1.45F,.55F,.22F), PartPose.ZERO);
        fittings.addOrReplaceChild("neck_ties", CubeListBuilder.create().texOffs(32,0)
                .addBox(-.62F,1.30F,-2.96F,1.24F,.13F,.16F).texOffs(32,0)
                .addBox(-.48F,1.88F,-2.96F,.96F,.13F,.16F), PartPose.ZERO);
        fittings.addOrReplaceChild("horizontal", CubeListBuilder.create().texOffs(32,0)
                .addBox(-4.26F,4.75F,-2.96F,8.52F,1,.21F).texOffs(32,0)
                .addBox(-4.26F,4.75F,2.75F,8.52F,1,.21F), PartPose.ZERO);
        fittings.addOrReplaceChild("vertical", CubeListBuilder.create().texOffs(32,0)
                .addBox(-.55F,1.25F,-2.96F,1,10.65F,.21F).texOffs(32,0)
                .addBox(-.55F,1.25F,2.75F,1,10.65F,.21F), PartPose.ZERO);
        fittings.addOrReplaceChild("twin_vertical", CubeListBuilder.create().texOffs(32,0)
                .addBox(-3.50F,1.4F,-2.96F,.64F,9.6F,.21F).texOffs(32,0)
                .addBox(2.86F,1.4F,-2.96F,.64F,9.6F,.21F).texOffs(32,0)
                .addBox(-3.50F,1.4F,2.75F,.64F,9.6F,.21F).texOffs(32,0)
                .addBox(2.86F,1.4F,2.75F,.64F,9.6F,.21F), PartPose.ZERO);
        fittings.addOrReplaceChild("sling_front", CubeListBuilder.create().texOffs(0,0)
                .addBox(-.36F,-5.6F,-.10F,.72F,11.2F,.20F),
                PartPose.offsetAndRotation(0,5.1F,-2.86F,0,0,-.60F));
        fittings.addOrReplaceChild("sling_back", CubeListBuilder.create().texOffs(0,0)
                .addBox(-.36F,-5.6F,-.10F,.72F,11.2F,.20F),
                PartPose.offsetAndRotation(0,5.1F,2.86F,0,0,.60F));
        fittings.addOrReplaceChild("sling_shoulder", CubeListBuilder.create().texOffs(0,0)
                .addBox(-3.5F,-.08F,-2.92F,1.05F,.28F,5.84F), PartPose.ZERO);
        var buttons = CubeListBuilder.create();
        for (float y : new float[]{3,5,7}) buttons.texOffs(0,32).addBox(-.18F,y,-2.965F,.36F,.36F,.18F);
        fittings.addOrReplaceChild("buttons", buttons, PartPose.ZERO);
        for (String side : java.util.List.of("left","right")) {
            boolean left = side.equals("left");
            var arm = root.addOrReplaceChild(side+"_arm", CubeListBuilder.create(), PartPose.offset(left?5:-5,2,0));
            arm.addOrReplaceChild("folded_cuff", CubeListBuilder.create().texOffs(32,0)
                    .addBox(left?-1:-2,7.55F,-2,3,.8F,4,new CubeDeformation(.46F)), PartPose.ZERO);
            var leg = root.addOrReplaceChild(side+"_leg", CubeListBuilder.create(), PartPose.offset(left?1.9F:-1.9F,12,0));
            leg.addOrReplaceChild("boot_binding", CubeListBuilder.create().texOffs(0,0)
                    .addBox(-2,9.15F,-2,4,.4F,4,new CubeDeformation(.4F)).texOffs(32,0)
                    .addBox(-.35F,9.2F,-2.49F,.7F,.13F,.18F).texOffs(32,0)
                    .addBox(-.3F,9.6F,-2.49F,.6F,.13F,.18F), PartPose.ZERO);
        }
        return LayerDefinition.create(mesh,64,64);
    }

    public void configure(NpcModel parent, NpcMilitaryOutfit outfit, NpcSocialStatus status, boolean legArmor) {
        configure(parent,outfit,status,false,legArmor);
    }
    public void configure(NpcModel parent, NpcMilitaryOutfit outfit, NpcSocialStatus status, boolean chestArmor, boolean legArmor) {
        root.getAllParts().forEach(ModelPart::resetPose);
        parent.copyPoseTo(this);
        boolean military = outfit != null;
        int style = military ? outfit.ordinal()%4 : -1;
        fittings.zScale = legArmor ? .835F : military ? 1 : .87F;
        fittings.getChild("waist_belt").visible = true;
        fittings.getChild("buckle").zScale = chestArmor || legArmor ? .95F : 1;
        fittings.getChild("belt_tail").visible = military || status != NpcSocialStatus.COMMONER;
        fittings.getChild("horizontal").visible = style == 0;
        fittings.getChild("vertical").visible = style == 1;
        fittings.getChild("twin_vertical").visible = style == 2 || !military && status.ordinal() >= NpcSocialStatus.LORD.ordinal();
        fittings.getChild("sling_front").visible = style == 3;
        fittings.getChild("sling_back").visible = style == 3;
        fittings.getChild("sling_shoulder").visible = style == 3;
        fittings.getChild("neck_ties").visible = !military || style == 2;
        fittings.getChild("buttons").visible = !military && status.ordinal() >= NpcSocialStatus.KNIGHT.ordinal();
        for (ModelPart leg : java.util.List.of(leftLeg,rightLeg)) {
            ModelPart binding = leg.getChild("boot_binding");
            binding.visible = military; // Civilian robes cover the ankles; do not strap over their hems.
            binding.xScale = binding.zScale = legArmor ? .95F : 1;
        }
    }
    @Override public void setupAnim(NpcRenderState state) {
        parent.setupAnim(state);
        var outfit = NpcMilitaryOutfit.find(state.clothingOutfit).orElseGet(() ->
                com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole.find(state.clothingOutfit)
                        .map(com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole::baseOutfit).orElse(null));
        configure(parent, outfit, state.socialStatus, state.chestArmored, state.legArmored);
    }

}
