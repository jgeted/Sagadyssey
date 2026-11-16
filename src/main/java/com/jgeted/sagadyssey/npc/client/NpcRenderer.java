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
    private final NpcRoleItemInHandLayer heldItems;

    public NpcRenderer(EntityRendererProvider.Context context) {
        super(context, new NpcModel(context.bakeLayer(SagadysseyClient.NPC_MODEL_LAYER)), 0.5F);
        this.layers.removeIf(layer -> layer instanceof net.minecraft.client.renderer.entity.layers.ItemInHandLayer);
        heldItems=new NpcRoleItemInHandLayer(this,context);
        this.addLayer(heldItems);
        this.addLayer(new NpcFaceLayer(
                this, new NpcFaceModel(context.bakeLayer(SagadysseyClient.NPC_FACE_LAYER), getModel())));
        this.addLayer(new NpcIdentityClothingLayer(
                this, new NpcIdentityClothingModel(
                        context.bakeLayer(SagadysseyClient.NPC_IDENTITY_CLOTHING_LAYER), getModel())));
        this.addLayer(new NpcUniformLayer(this, new NpcUniformModel(context.bakeLayer(SagadysseyClient.NPC_UNIFORM_LAYER), getModel())));
        this.addLayer(new NpcClothingDetailsLayer(this, new NpcClothingDetailsModel(context.bakeLayer(SagadysseyClient.NPC_CLOTHING_DETAILS_LAYER), getModel())));
        this.addLayer(new NpcClothingReliefLayer(this, new NpcClothingReliefModel(context.bakeLayer(SagadysseyClient.NPC_CLOTHING_RELIEF_LAYER), getModel())));
        this.addLayer(new NpcMilitaryRoleLayer(this, new NpcMilitaryRoleModel(context.bakeLayer(SagadysseyClient.NPC_MILITARY_ROLE_LAYER), getModel())));
        this.addLayer(new NpcLogisticsStatusLayer(this));
        // 人物脸、身份服装与职业服饰均在护甲之下；装备槽护甲始终使用原版护甲层。
        this.addLayer(new HumanoidArmorLayer<>(this,
                SagadysseyClient.NPC_ARMOR_LAYERS.map(layer -> new NpcArmorModel(context.bakeLayer(layer), getModel())),
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
        var appearance = npc.getAppearanceProfile();
        state.headShape = appearance.head();
        state.hairStyle = appearance.hair();
        state.skinTone = appearance.skin();
        state.socialStatus = npc.getSocialStatus();
        state.profession = npc.getProfession();
        state.action = npc.getVisualAction();
        state.actionProgress = npc.getActionProgress(partialTick);
        state.helmeted = !npc.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        state.mounted = npc.isPassenger();
        state.chestArmored = !npc.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
        state.legArmored = !npc.getItemBySlot(EquipmentSlot.LEGS).isEmpty();
        state.militaryClothing = npc.hasMilitaryClothing();
        state.clothingOutfit = npc.getClothingOutfit();
        state.clothingFaction = npc.getClothingFactionId();
        state.clothingPalette = npc.getClothingPaletteOverride();
        if (npc.getVehicle() instanceof net.minecraft.world.entity.animal.equine.AbstractHorse mount && mount.shouldRiderSit()) {
            state.bodyRot = com.jgeted.sagadyssey.npc.appearance.NpcRidingAngles.bodyYaw(mount.yBodyRotO, mount.yBodyRot, partialTick);
            state.yRot = com.jgeted.sagadyssey.npc.appearance.NpcRidingAngles.headYaw(npc.yHeadRotO, npc.yHeadRot, state.bodyRot, partialTick);
        }
        state.resting = npc.isSleeping();
        state.standardInOffhand = npc.getOffhandItem().getItem() instanceof net.minecraft.world.item.BannerItem;
        state.carryingStandard = !state.resting
                && com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole.find(state.clothingOutfit)
                    .filter(role -> role == com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole.STANDARD_BEARER).isPresent()
                && (state.standardInOffhand || npc.getMainHandItem().getItem() instanceof net.minecraft.world.item.BannerItem);
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
        return NpcAppearanceTextures.body(state);
    }
    NpcCarriedStandard carriedStandard(){return heldItems.standard();}
    static Identifier professionTexture(NpcProfession profession) {
        return PROFESSION_TEXTURES.getOrDefault(profession, FALLBACK);
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
                "sagadyssey", "textures/entity/npc/revision_v2/profession/" + name + ".png");
    }
}
