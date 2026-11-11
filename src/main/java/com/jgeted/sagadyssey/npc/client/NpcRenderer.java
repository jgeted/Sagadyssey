package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.core.SagadysseyClient;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Map;

public class NpcRenderer extends HumanoidMobRenderer<NpcBase, NpcRenderState, NpcModel> {

    private static final Map<NpcProfession, Identifier> PROFESSION_TEXTURES = createTextureMap();
    private static final Identifier FALLBACK = texture("none");

    public NpcRenderer(EntityRendererProvider.Context context) {
        super(context, new NpcModel(context.bakeLayer(SagadysseyClient.NPC_MODEL_LAYER)), 0.5F);
        this.addLayer(new NpcFaceLayer(
                this, new NpcFaceModel(context.bakeLayer(SagadysseyClient.NPC_FACE_LAYER), getModel())));
        this.addLayer(new NpcIdentityClothingLayer(
                this, new NpcIdentityClothingModel(
                        context.bakeLayer(SagadysseyClient.NPC_IDENTITY_CLOTHING_LAYER), getModel())));
        this.addLayer(new NpcLogisticsStatusLayer(this));
        // 人物脸、身份服装与职业服饰均在护甲之下；装备槽护甲始终使用原版护甲层。
        this.addLayer(new HumanoidArmorLayer<>(this,
                SagadysseyClient.NPC_ARMOR_LAYERS.map(layer -> new HumanoidModel<NpcRenderState>(context.bakeLayer(layer))),
                context.getEquipmentRenderer()));
    }

    @Override
    public NpcRenderState createRenderState() {
        return new NpcRenderState();
    }

    @Override
    protected HumanoidModel.ArmPose getArmPose(NpcBase npc, HumanoidArm arm) {
        if (npc.isSleeping()) return HumanoidModel.ArmPose.EMPTY;
        var main = NpcModel.armPoseFor(npc, InteractionHand.MAIN_HAND);
        if (npc.getMainArm() == arm) return main;
        if (main.isTwoHanded()) {
            return npc.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        return NpcModel.armPoseFor(npc, InteractionHand.OFF_HAND);
    }

    @Override
    public void extractRenderState(NpcBase npc, NpcRenderState state, float partialTick) {
        super.extractRenderState(npc, state, partialTick);
        state.gender = npc.getGender();
        state.face = npc.getFaceVariant();
        state.socialStatus = npc.getSocialStatus();
        state.profession = npc.getProfession();
        state.action = npc.getVisualAction();
        state.actionProgress = npc.getActionProgress(partialTick);
        state.helmeted = !npc.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        state.mounted = npc.isPassenger();
        state.resting = npc.isSleeping();
        if (state.resting) {
            state.leftHandItemState.clear();
            state.rightHandItemState.clear();
        }
        state.logisticsStage = npc.getLogisticsStage();
        var player = Minecraft.getInstance().player;
        if (state.logisticsStage == 0 || player == null || player.distanceToSqr(npc) > 48.0D * 48.0D
                || npc.getSyncedOwnerUUID().filter(player.getUUID()::equals).isEmpty()) {
            state.logisticsText = null;
        } else {
            String rawItem = npc.getLogisticsItem();
            Identifier itemId = Identifier.tryParse(rawItem);
            String item = itemId == null ? rawItem : itemId.getPath();
            state.logisticsText = state.logisticsStage == 3 ? "护送 " + item
                    : (state.logisticsStage == 1 ? "取货 " : "送货 ") + item + "  "
                        + npc.getLogisticsDelivered() + "/" + npc.getLogisticsRequested()
                        + (npc.getLogisticsCargo() > 0 ? "  货舱:" + npc.getLogisticsCargo() : "");
        }
    }

    @Override
    public Identifier getTextureLocation(NpcRenderState state) {
        return PROFESSION_TEXTURES.getOrDefault(state.profession, FALLBACK);
    }

    private static Map<NpcProfession, Identifier> createTextureMap() {
        Map<NpcProfession, Identifier> textures = new EnumMap<>(NpcProfession.class);
        for (NpcProfession profession : NpcProfession.values()) {
            textures.put(profession, texture(profession == NpcProfession.COURIER
                    ? "trader" : profession.name().toLowerCase()));
        }
        return textures;
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(
                "sagadyssey", "textures/entity/npc/" + name + ".png");
    }
}
