package com.jgeted.sagadyssey.npc.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.ArmorModelSet;

/** Runtime and art inspection use the same vanilla armor shells. */
public final class NpcArmorModels {
    private NpcArmorModels() {}

    public static ArmorModelSet<LayerDefinition> createLayers() {
        return HumanoidModel.createArmorMeshSet(
                LayerDefinitions.INNER_ARMOR_DEFORMATION,
                LayerDefinitions.OUTER_ARMOR_DEFORMATION)
                .map(mesh -> LayerDefinition.create(mesh, 64, 32));
    }
}
