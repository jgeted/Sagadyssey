package com.jgeted.sagadyssey.npc.client;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

public final class NpcClothingReliefLayer extends RenderLayer<NpcRenderState,NpcModel> {
    public static final net.minecraft.resources.Identifier TEXTURE = net.minecraft.resources.Identifier.fromNamespaceAndPath("sagadyssey", "textures/entity/npc/clothing_relief/materials.png");
    private final NpcClothingReliefModel model;
    public NpcClothingReliefLayer(RenderLayerParent<NpcRenderState,NpcModel> parent, NpcClothingReliefModel model) { super(parent); this.model=model; }
    @Override public void submit(PoseStack poses, SubmitNodeCollector collector, int light, NpcRenderState state, float yaw, float pitch) {
        if (state.isInvisible || false) return;
        coloredCutoutModelCopyLayerRender(model, TEXTURE, poses, collector, light, state, 0xFFFFFFFF, 5);
    }
}
