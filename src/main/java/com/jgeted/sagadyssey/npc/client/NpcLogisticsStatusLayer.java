package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 在正在运输的 NPC 头顶显示取货/送货进度。 */
public final class NpcLogisticsStatusLayer extends RenderLayer<NpcRenderState, NpcModel> {
    public NpcLogisticsStatusLayer(RenderLayerParent<NpcRenderState, NpcModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       NpcRenderState state, float yRot, float xRot) {
        if (state.logisticsText == null) return;
        poseStack.pushPose();
        poseStack.translate(0.0D, state.boundingBoxHeight + 0.65D, 0.0D);
        poseStack.mulPose(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        Font font = Minecraft.getInstance().font;
        Component component = Component.literal(state.logisticsText);
        float x = -font.width(component) / 2.0F;
        int color = state.logisticsStage == 1 ? 0xFFFFC642 : state.logisticsStage == 2 ? 0xFF55DDFF : 0xFF66DD88;
        int background = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.35F) * 255.0F) << 24;
        collector.submitText(poseStack, x, 0.0F, component.getVisualOrderText(), false,
                Font.DisplayMode.SEE_THROUGH, packedLight, color, background, 0);
        poseStack.popPose();
    }
}
