package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 在正在运输的 NPC 头顶显示取货/送货进度。 */
public final class NpcLogisticsStatusLayer extends RenderLayer<NpcBase, NpcModel> {
    public NpcLogisticsStatusLayer(RenderLayerParent<NpcBase, NpcModel> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       NpcBase entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        int stage = entity.getLogisticsStage();
        if (stage == 0 || Minecraft.getInstance().player == null
                || entity.getSyncedOwnerUUID().filter(Minecraft.getInstance().player.getUUID()::equals).isEmpty()
                || Minecraft.getInstance().player.distanceToSqr(entity) > 48.0D * 48.0D) return;

        String rawItem = entity.getLogisticsItem();
        ResourceLocation itemId = ResourceLocation.tryParse(rawItem);
        String item = itemId == null ? rawItem : itemId.getPath();
        String text = stage == 3 ? "护送 " + item
                : (stage == 1 ? "取货 " : "送货 ") + item + "  "
                    + entity.getLogisticsDelivered() + "/" + entity.getLogisticsRequested()
                    + (entity.getLogisticsCargo() > 0 ? "  货舱:" + entity.getLogisticsCargo() : "");

        poseStack.pushPose();
        poseStack.translate(0.0D, entity.getBbHeight() + 0.65D, 0.0D);
        poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        Font font = Minecraft.getInstance().font;
        Component component = Component.literal(text);
        float x = -font.width(component) / 2.0F;
        int color = stage == 1 ? 0xFFFFC642 : stage == 2 ? 0xFF55DDFF : 0xFF66DD88;
        int background = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.35F) * 255.0F) << 24;
        font.drawInBatch(component, x, 0.0F, color, false, poseStack.last().pose(), buffer,
                Font.DisplayMode.SEE_THROUGH, background, packedLight);
        poseStack.popPose();
    }
}
