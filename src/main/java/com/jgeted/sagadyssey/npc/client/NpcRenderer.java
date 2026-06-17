package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * NPC 渲染器。暂时用僵尸模型+纹理占位，后续替换为专用模型。
 */
public class NpcRenderer extends HumanoidMobRenderer<NpcBase, HumanoidModel<NpcBase>> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/zombie/zombie.png");

    public NpcRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        LOGGER.info("NPC renderer layers count: {}", this.layers.size());
        for (var layer : this.layers) {
            LOGGER.info("  Layer class: {}", layer.getClass().getSimpleName());
        }
    }

    @Override
    public ResourceLocation getTextureLocation(NpcBase entity) {
        return TEXTURE;
    }
}
