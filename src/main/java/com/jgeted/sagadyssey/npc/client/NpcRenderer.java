package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.core.SagadysseyClient;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;

public class NpcRenderer extends HumanoidMobRenderer<NpcBase, NpcModel> {

    private static final Map<NpcProfession, ResourceLocation> PROFESSION_TEXTURES = createTextureMap();
    private static final ResourceLocation FALLBACK = texture("none");

    public NpcRenderer(EntityRendererProvider.Context context) {
        super(context, new NpcModel(context.bakeLayer(SagadysseyClient.NPC_MODEL_LAYER)), 0.5F);
        this.addLayer(new NpcFaceLayer(
                this, new NpcFaceModel(context.bakeLayer(SagadysseyClient.NPC_FACE_LAYER))));
        this.addLayer(new NpcIdentityClothingLayer(
                this, new NpcIdentityClothingModel(
                        context.bakeLayer(SagadysseyClient.NPC_IDENTITY_CLOTHING_LAYER))));
        this.addLayer(new NpcLogisticsStatusLayer(this));
        // 人物脸、身份服装与职业服饰均在护甲之下；装备槽护甲始终使用原版护甲层。
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidArmorModel<NpcBase>(context.bakeLayer(SagadysseyClient.NPC_INNER_ARMOR_LAYER)),
                new HumanoidArmorModel<NpcBase>(context.bakeLayer(SagadysseyClient.NPC_OUTER_ARMOR_LAYER)),
                context.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(NpcBase entity) {
        return PROFESSION_TEXTURES.getOrDefault(entity.getProfession(), FALLBACK);
    }

    private static Map<NpcProfession, ResourceLocation> createTextureMap() {
        Map<NpcProfession, ResourceLocation> textures = new EnumMap<>(NpcProfession.class);
        for (NpcProfession profession : NpcProfession.values()) {
            textures.put(profession, texture(profession == NpcProfession.COURIER
                    ? "trader" : profession.name().toLowerCase()));
        }
        return textures;
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                "sagadyssey", "textures/entity/npc/" + name + ".png");
    }
}
