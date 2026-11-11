package com.jgeted.sagadyssey.core;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.gui.ResearchScreen;
import com.jgeted.sagadyssey.core.network.ResearchSyncPayload;
import com.jgeted.sagadyssey.npc.command.network.ClientCommandGroupSyncHandler;
import com.jgeted.sagadyssey.npc.command.network.CommandGroupSyncPayload;
import com.jgeted.sagadyssey.npc.faction.network.FactionDataSyncPayload;
import com.jgeted.sagadyssey.npc.faction.network.FactionStandingsUpdatePayload;
import com.jgeted.sagadyssey.npc.network.ClientHandoverPromptHandler;
import com.jgeted.sagadyssey.npc.network.ClientNpcViewSnapshotPayloadHandler;
import com.jgeted.sagadyssey.npc.network.ClientTradeSessionHandler;
import com.jgeted.sagadyssey.npc.network.HandoverPromptPayload;
import com.jgeted.sagadyssey.npc.network.NpcViewSnapshotPayload;
import com.jgeted.sagadyssey.npc.network.TradeSessionPayload;

import com.jgeted.sagadyssey.npc.client.NpcModel;
import com.jgeted.sagadyssey.npc.client.NpcFaceModel;
import com.jgeted.sagadyssey.npc.client.NpcIdentityClothingModel;
import com.jgeted.sagadyssey.npc.client.NpcRenderer;
import com.jgeted.sagadyssey.npc.faction.gui.ReputationChartScreen;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端专用初始化。注册按键绑定。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = Sagadyssey.MOD_ID)
public class SagadysseyClient {
    public static final ModelLayerLocation NPC_MODEL_LAYER =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath("sagadyssey", "npc"), "main");
    public static final ModelLayerLocation NPC_FACE_LAYER =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath("sagadyssey", "npc"), "face");
    public static final ModelLayerLocation NPC_IDENTITY_CLOTHING_LAYER =
            new ModelLayerLocation(Identifier.fromNamespaceAndPath("sagadyssey", "npc"), "identity_clothing");
    public static final ArmorModelSet<ModelLayerLocation> NPC_ARMOR_LAYERS = new ArmorModelSet<>(
            armorLayer("head"), armorLayer("chest"), armorLayer("legs"), armorLayer("feet"));

    private static ModelLayerLocation armorLayer(String part) {
        return new ModelLayerLocation(Identifier.fromNamespaceAndPath("sagadyssey", "npc"), "armor_" + part);
    }

    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sagadyssey", "main"));

    public static final KeyMapping RESEARCH_KEY = new KeyMapping(
            "key.sagadyssey.research",
            GLFW.GLFW_KEY_K,
            KEY_CATEGORY
    );

    public static final KeyMapping REPUTATION_KEY = new KeyMapping(
            "key.sagadyssey.reputation",
            GLFW.GLFW_KEY_R,
            KEY_CATEGORY
    );

    @SubscribeEvent
    public static void registerClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        if (LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED) {
            event.register(ResearchSyncPayload.TYPE, ResearchSyncPayload::handle);
        }
        event.register(NpcViewSnapshotPayload.TYPE, ClientNpcViewSnapshotPayloadHandler::handle);
        event.register(HandoverPromptPayload.TYPE, ClientHandoverPromptHandler::handle);
        event.register(TradeSessionPayload.TYPE, ClientTradeSessionHandler::handle);
        event.register(CommandGroupSyncPayload.TYPE, ClientCommandGroupSyncHandler::handle);
        event.register(FactionDataSyncPayload.TYPE, FactionDataSyncPayload::handle);
        event.register(FactionStandingsUpdatePayload.TYPE, FactionStandingsUpdatePayload::handle);
    }

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
        var mesh = HumanoidModel.createArmorMeshSet(new CubeDeformation(0.35F), new CubeDeformation(0.68F))
                .map(part -> LayerDefinition.create(part, 64, 32));
        event.registerLayerDefinition(NPC_ARMOR_LAYERS.head(), mesh::head);
        event.registerLayerDefinition(NPC_ARMOR_LAYERS.chest(), mesh::chest);
        event.registerLayerDefinition(NPC_ARMOR_LAYERS.legs(), mesh::legs);
        event.registerLayerDefinition(NPC_ARMOR_LAYERS.feet(), mesh::feet);
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
