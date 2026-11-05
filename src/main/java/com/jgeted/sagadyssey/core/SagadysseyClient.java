package com.jgeted.sagadyssey.core;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.gui.ResearchScreen;

import com.jgeted.sagadyssey.npc.client.NpcModel;
import com.jgeted.sagadyssey.npc.client.NpcFaceModel;
import com.jgeted.sagadyssey.npc.client.NpcIdentityClothingModel;
import com.jgeted.sagadyssey.npc.client.NpcRenderer;
import com.jgeted.sagadyssey.npc.faction.gui.ReputationChartScreen;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端专用初始化。注册按键绑定。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = Sagadyssey.MOD_ID)
public class SagadysseyClient {
    public static final ModelLayerLocation NPC_MODEL_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("sagadyssey", "npc"), "main");
    public static final ModelLayerLocation NPC_FACE_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("sagadyssey", "npc"), "face");
    public static final ModelLayerLocation NPC_IDENTITY_CLOTHING_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("sagadyssey", "npc"), "identity_clothing");
    public static final ModelLayerLocation NPC_INNER_ARMOR_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("sagadyssey", "npc"), "inner_armor");
    public static final ModelLayerLocation NPC_OUTER_ARMOR_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("sagadyssey", "npc"), "outer_armor");

    public static final KeyMapping RESEARCH_KEY = new KeyMapping(
            "key.sagadyssey.research",
            GLFW.GLFW_KEY_K,
            "key.categories.sagadyssey"
    );

    public static final KeyMapping REPUTATION_KEY = new KeyMapping(
            "key.sagadyssey.reputation",
            GLFW.GLFW_KEY_R,
            "key.categories.sagadyssey"
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        if (LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED) {
            event.register(RESEARCH_KEY);
        }
        event.register(REPUTATION_KEY);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(NPC_MODEL_LAYER, NpcModel::createBodyLayer);
        event.registerLayerDefinition(NPC_FACE_LAYER, NpcFaceModel::createBodyLayer);
        event.registerLayerDefinition(NPC_IDENTITY_CLOTHING_LAYER, NpcIdentityClothingModel::createBodyLayer);
        // 原版 outer armor 的 1.0 膨胀会形成夸张方肩；NPC 使用更贴身、仍完全独立的护甲模型。
        event.registerLayerDefinition(NPC_INNER_ARMOR_LAYER, () -> LayerDefinition.create(
                HumanoidArmorModel.createBodyLayer(new CubeDeformation(0.35F)), 64, 32));
        event.registerLayerDefinition(NPC_OUTER_ARMOR_LAYER, () -> LayerDefinition.create(
                HumanoidArmorModel.createBodyLayer(new CubeDeformation(0.68F)), 64, 32));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(NpcEntityTypes.NPC_BASE.get(), NpcRenderer::new);
    }


    @EventBusSubscriber(value = Dist.CLIENT)
    public static class KeyHandler {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED) {
                while (RESEARCH_KEY.consumeClick()) {
                    Minecraft.getInstance().setScreen(new ResearchScreen());
                }
            }
            while (REPUTATION_KEY.consumeClick()) {
                Minecraft.getInstance().setScreen(new ReputationChartScreen());
            }
        }
    }
}
