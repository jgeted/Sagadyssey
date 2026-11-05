package com.jgeted.sagadyssey.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/** 使用独立资源渲染脸与发型，避免为每个职业复制一整套人物皮肤。 */
public final class NpcFaceLayer extends RenderLayer<NpcBase, NpcModel> {
    private final NpcFaceModel model;

    public NpcFaceLayer(RenderLayerParent<NpcBase, NpcModel> parent, NpcFaceModel model) {
        super(parent);
        this.model = model;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       NpcBase entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        model.configure(entity);
        coloredCutoutModelCopyLayerRender(getParentModel(), model, texture(entity),
                poseStack, buffer, packedLight, entity, limbSwing, limbSwingAmount,
                ageInTicks, netHeadYaw, headPitch, partialTick, 0xFFFFFFFF);
    }

    private static ResourceLocation texture(NpcBase entity) {
        String name = entity.getGender().id() + "_" + entity.getFaceVariant().id();
        return ResourceLocation.fromNamespaceAndPath(
                "sagadyssey", "textures/entity/npc/face/" + name + ".png");
    }
}
