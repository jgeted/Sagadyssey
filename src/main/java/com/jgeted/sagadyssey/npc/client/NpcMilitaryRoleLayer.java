package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

public final class NpcMilitaryRoleLayer extends RenderLayer<NpcRenderState,NpcModel> {
    private final NpcMilitaryRoleModel model;
    public NpcMilitaryRoleLayer(RenderLayerParent<NpcRenderState,NpcModel> parent, NpcMilitaryRoleModel model) {
        super(parent); this.model = model;
    }
    @Override public void submit(PoseStack poses, SubmitNodeCollector collector, int light, NpcRenderState state, float yaw, float pitch) {
        if (state.isInvisible || NpcMilitaryRole.find(state.clothingOutfit).isEmpty()) return;
        coloredCutoutModelCopyLayerRender(model, NpcOutfitTextures.roleAccessories(state), poses, collector, light, state, 0xFFFFFFFF, 6);
    }
}
