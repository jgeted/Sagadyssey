package com.jgeted.sagadyssey.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.Identifier;

/** 使用独立资源渲染脸与发型，避免为每个职业复制一整套人物皮肤。 */
public final class NpcFaceLayer extends RenderLayer<NpcRenderState, NpcModel> {
    private final NpcFaceModel model;

    public NpcFaceLayer(RenderLayerParent<NpcRenderState, NpcModel> parent, NpcFaceModel model) {
        super(parent);
        this.model = model;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       NpcRenderState state, float yRot, float xRot) {
        coloredCutoutModelCopyLayerRender(model, texture(state), poseStack, collector,
                packedLight, state, 0xFFFFFFFF, 1);
    }

    private static Identifier texture(NpcRenderState state) {
        String name = state.gender.id() + "_" + state.face.id();
        return Identifier.fromNamespaceAndPath(
                "sagadyssey", "textures/entity/npc/face/" + name + ".png");
    }
}
