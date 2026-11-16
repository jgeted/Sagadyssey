package com.jgeted.sagadyssey.npc.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

/** Preserve the NPC's proportions and riding/action pose during deferred armor rendering. */
public final class NpcArmorModel extends HumanoidModel<NpcRenderState> {
    private final NpcModel parent;

    public NpcArmorModel(ModelPart root, NpcModel parent) {
        super(root);
        this.parent = parent;
    }

    @Override
    public void setupAnim(NpcRenderState state) {
        parent.setupAnim(state);
        parent.copyPoseTo(this);
    }
}
