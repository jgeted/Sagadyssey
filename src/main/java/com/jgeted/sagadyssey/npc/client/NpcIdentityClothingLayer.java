package com.jgeted.sagadyssey.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/** 将身份服装作为独立渲染层叠加在人物/职业皮肤之外。 */
public final class NpcIdentityClothingLayer extends RenderLayer<NpcBase, NpcModel> {
    private final NpcIdentityClothingModel model;

    public NpcIdentityClothingLayer(RenderLayerParent<NpcBase, NpcModel> parent,
                                    NpcIdentityClothingModel model) {
        super(parent);
        this.model = model;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       NpcBase entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) return;
        model.copyAndConfigure(getParentModel(), entity);
        renderColoredCutoutModel(model, texture(entity), poseStack, buffer,
                packedLight, entity, 0xFFFFFFFF);
    }

    private static ResourceLocation texture(NpcBase entity) {
        String name = entity.getSocialStatus().id() + "_" + entity.getGender().id();
        return ResourceLocation.fromNamespaceAndPath(
                "sagadyssey", "textures/entity/npc/identity/" + name + ".png");
    }
}
