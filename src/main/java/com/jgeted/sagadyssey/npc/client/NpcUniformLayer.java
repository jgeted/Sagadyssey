package com.jgeted.sagadyssey.npc.client;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

public final class NpcUniformLayer extends RenderLayer<NpcRenderState,NpcModel> {
    private final NpcUniformModel model;
    public NpcUniformLayer(RenderLayerParent<NpcRenderState,NpcModel> parent, NpcUniformModel model) { super(parent); this.model=model; }
    @Override public void submit(PoseStack poses, SubmitNodeCollector collector, int light, NpcRenderState state, float yaw, float pitch) {
        if (state.isInvisible || !state.militaryClothing) return;
        coloredCutoutModelCopyLayerRender(model, NpcOutfitTextures.military(state), poses, collector, light, state, 0xFFFFFFFF, 3);
    }
}
