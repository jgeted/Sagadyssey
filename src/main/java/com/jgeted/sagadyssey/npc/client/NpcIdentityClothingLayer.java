package com.jgeted.sagadyssey.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.Identifier;

/** 将身份服装作为独立渲染层叠加在人物/职业皮肤之外。 */
public final class NpcIdentityClothingLayer extends RenderLayer<NpcRenderState, NpcModel> {
    private final NpcIdentityClothingModel model;

    public NpcIdentityClothingLayer(RenderLayerParent<NpcRenderState, NpcModel> parent,
                                    NpcIdentityClothingModel model) {
        super(parent);
        this.model = model;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       NpcRenderState state, float yRot, float xRot) {
        coloredCutoutModelCopyLayerRender(model, texture(state), poseStack, collector,
                packedLight, state, 0xFFFFFFFF, 2);
    }

    private static Identifier texture(NpcRenderState state) {
        String name = state.socialStatus.id() + "_" + state.gender.id();
        return Identifier.fromNamespaceAndPath(
                "sagadyssey", "textures/entity/npc/identity/" + name + ".png");
    }
}
