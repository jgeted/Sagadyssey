package com.jgeted.sagadyssey.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/** Keep vanilla item rendering, with an upright full-size standard only for its actual carrier. */
public final class NpcRoleItemInHandLayer extends ItemInHandLayer<NpcRenderState,NpcModel> {
    private final NpcCarriedStandard standard;
    public NpcRoleItemInHandLayer(RenderLayerParent<NpcRenderState,NpcModel> parent,
                                 net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) {
        super(parent);standard=new NpcCarriedStandard(context);
    }
    NpcCarriedStandard standard(){return standard;}

    @Override protected void submitArmWithItem(NpcRenderState state, ItemStackRenderState itemState, ItemStack item,
                                              HumanoidArm arm, PoseStack poses, SubmitNodeCollector collector, int light) {
        if (!state.carryingStandard || arm != state.standardArm() || !(item.getItem() instanceof net.minecraft.world.item.BannerItem)) {
            super.submitArmWithItem(state,itemState,item,arm,poses,collector,light);
            return;
        }
        poses.pushPose();
        standardGrip(getParentModel(),state,arm,poses);
        standard.submit(item,poses,collector,light,state.outlineColor);
        poses.popPose();
    }

    static void standardGrip(NpcModel model, NpcRenderState state, HumanoidArm side, PoseStack poses) {
        model.translateToPalm(state,side,poses);
        var arm = side == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        // Preserve the palm location, then remove arm rotation/scale from the upright pole.
        poses.scale(1/arm.xScale,1/arm.yScale,1/arm.zScale);
        poses.mulPose(Axis.XP.rotation(-arm.xRot));
        poses.mulPose(Axis.YP.rotation(-arm.yRot));
        poses.mulPose(Axis.ZP.rotation(-arm.zRot));
        poses.mulPose(Axis.XP.rotationDegrees(180));
        // ItemTransform.NONE still translates (-.5,-.5,-.5). BannerRenderer then
        // adds (.5,0,.5): X/Z already cancel. Only its Y origin needs compensation.
        // The carried cloth is shorter, leaving shaft above and below the palm.
        poses.translate(0,.5F-NpcCarriedStandard.GRIP_HEIGHT,0);
    }
}
