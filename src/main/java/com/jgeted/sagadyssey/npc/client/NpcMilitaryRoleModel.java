package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;

/** Closed-volume bags, pack straps, tool rolls and keys attached to the existing skeleton. */
public final class NpcMilitaryRoleModel extends HumanoidModel<NpcRenderState> {
    private final NpcModel parent;
    private final ModelPart root, equipment;

    public NpcMilitaryRoleModel(ModelPart root, NpcModel parent) {
        super(root); this.root = root; this.parent = parent;
        equipment = body.getChild("role_equipment");
    }

    public static LayerDefinition createBodyLayer() {
        var mesh = new MeshDefinition(); var root = mesh.getRoot();
        var head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        var body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        var eq = body.addOrReplaceChild("role_equipment", CubeListBuilder.create(), PartPose.ZERO);
        eq.addOrReplaceChild("document_case", CubeListBuilder.create().texOffs(0,0)
                .addBox(2.6F,9.5F,1.9F,2.8F,4.2F,1.8F).texOffs(0,8)
                .addBox(2.5F,9.35F,1.8F,3F,1.3F,2F).texOffs(32,0)
                .addBox(3.7F,10.2F,3.78F,.55F,.6F,.14F), PartPose.ZERO);
        eq.addOrReplaceChild("belt_purse", CubeListBuilder.create().texOffs(0,0)
                .addBox(-3.4F,9.6F,-3.5F,2.1F,2.2F,.9F).texOffs(0,8)
                .addBox(-3.45F,9.5F,-3.65F,2.2F,.65F,1.05F), PartPose.ZERO);
        eq.addOrReplaceChild("tool_roll", CubeListBuilder.create().texOffs(0,16)
                .addBox(-3.3F,9.9F,2.75F,5.8F,1.7F,1.6F).texOffs(0,16)
                .addBox(-3.1F,9.6F,2.95F,5.4F,2.25F,1.2F).texOffs(0,0)
                .addBox(-2.4F,9.5F,2.7F,.45F,2.4F,1.8F).texOffs(0,0)
                .addBox(1.1F,9.5F,2.7F,.45F,2.4F,1.8F), PartPose.ZERO);
        eq.addOrReplaceChild("cargo_pack", CubeListBuilder.create().texOffs(0,32)
                .addBox(-3.2F,2.6F,2.75F,6.4F,6.4F,3F).texOffs(0,32)
                .addBox(-3.3F,2.4F,2.65F,6.6F,1.6F,3.2F).texOffs(0,0)
                .addBox(-2.05F,3.5F,5.73F,.55F,4.7F,.18F).texOffs(0,0)
                .addBox(1.5F,3.5F,5.73F,.55F,4.7F,.18F), PartPose.ZERO);
        eq.addOrReplaceChild("bedroll", CubeListBuilder.create().texOffs(32,32)
                .addBox(-3.6F,.8F,3F,7.2F,1.8F,2.4F).texOffs(32,32)
                .addBox(-3.45F,.55F,3.3F,6.9F,2.3F,1.8F).texOffs(0,0)
                .addBox(-2.4F,.5F,2.95F,.5F,2.4F,2.5F).texOffs(0,0)
                .addBox(1.9F,.5F,2.95F,.5F,2.4F,2.5F), PartPose.ZERO);
        eq.addOrReplaceChild("pack_straps", CubeListBuilder.create().texOffs(0,0)
                .addBox(-2.5F,.05F,-2.87F,.65F,8.6F,.23F).texOffs(0,0)
                .addBox(1.85F,.05F,-2.87F,.65F,8.6F,.23F).texOffs(0,0)
                .addBox(-2.5F,-.1F,-2.65F,.65F,.3F,5.45F).texOffs(0,0)
                .addBox(1.85F,-.1F,-2.65F,.65F,.3F,5.45F), PartPose.ZERO);
        // Four bars form a real ring, with two short, solid keys suspended from it.
        var keys = CubeListBuilder.create().texOffs(32,0)
                .addBox(2.45F,9.6F,-3.05F,1F,.14F,.2F).texOffs(32,0)
                .addBox(2.45F,10.4F,-3.05F,1F,.14F,.2F).texOffs(32,0)
                .addBox(2.45F,9.74F,-3.05F,.14F,.66F,.2F).texOffs(32,0)
                .addBox(3.31F,9.74F,-3.05F,.14F,.66F,.2F);
        for (float x : new float[]{2.6F,3.05F}) keys.texOffs(32,0)
                .addBox(x,10.5F,-3.03F,.16F,1.65F,.16F).texOffs(32,0)
                .addBox(x,11.83F,-3.03F,.42F,.25F,.16F);
        eq.addOrReplaceChild("keys", keys, PartPose.ZERO);
        eq.addOrReplaceChild("standard_socket", CubeListBuilder.create().texOffs(0,0)
                .addBox(-3.2F,9.55F,-3.15F,1.1F,.2F,.8F).texOffs(0,0)
                .addBox(-3.2F,10.55F,-3.15F,1.1F,.2F,.8F).texOffs(0,0)
                .addBox(-3.2F,9.75F,-3.15F,.2F,.8F,.8F).texOffs(0,0)
                .addBox(-2.3F,9.75F,-3.15F,.2F,.8F,.8F), PartPose.ZERO);
        for (String side : java.util.List.of("left","right")) {
            boolean left = side.equals("left");
            var arm = root.addOrReplaceChild(side+"_arm", CubeListBuilder.create(), PartPose.offset(left?5:-5,2,0));
            arm.addOrReplaceChild("livery_mark", CubeListBuilder.create().texOffs(0,32)
                    .addBox(left?-.45F:-1.55F,.8F,-2.57F,2F,1.7F,.19F).texOffs(32,32)
                    .addBox(left?.25F:-.85F,.85F,-2.62F,.5F,1.6F,.12F), PartPose.ZERO);
            root.addOrReplaceChild(side+"_leg", CubeListBuilder.create(), PartPose.offset(left?1.9F:-1.9F,12,0));
        }
        return LayerDefinition.create(mesh,64,64);
    }

    void configure(NpcModel parent, NpcMilitaryRole role, boolean chestArmor) {
        root.getAllParts().forEach(ModelPart::resetPose);
        parent.copyPoseTo(this);
        equipment.visible = role != null;
        leftArm.getChild("livery_mark").visible = role != null;
        rightArm.getChild("livery_mark").visible = role == NpcMilitaryRole.TROOP_CAPTAIN;
        for (ModelPart part : equipment.getAllParts()) if (part != equipment) part.visible = false;
        if (role == null) return;
        equipment.getChild("document_case").visible = role.documents();
        equipment.getChild("belt_purse").visible = role != NpcMilitaryRole.MILITARY_CARRIER;
        equipment.getChild("tool_roll").visible = role.tools();
        equipment.getChild("keys").visible = role == NpcMilitaryRole.QUARTERMASTER;
        equipment.getChild("standard_socket").visible = role == NpcMilitaryRole.STANDARD_BEARER;
        equipment.getChild("cargo_pack").visible = role == NpcMilitaryRole.MILITARY_CARRIER;
        equipment.getChild("bedroll").visible = role == NpcMilitaryRole.MILITARY_CARRIER;
        equipment.getChild("pack_straps").visible = role == NpcMilitaryRole.MILITARY_CARRIER && !chestArmor;
    }

    @Override public void setupAnim(NpcRenderState state) {
        parent.setupAnim(state);
        configure(parent, NpcMilitaryRole.find(state.clothingOutfit).orElse(null), state.chestArmored);
    }
}
