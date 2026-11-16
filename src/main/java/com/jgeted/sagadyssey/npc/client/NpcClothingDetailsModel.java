package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * 增量民用服饰：只含袖口、短肩衣和落肩兜帽，不复制或替换原身体、脸及身份服装。
 * UV 复用现有 128x64 职业贴图右半边的织物区，不增加或覆盖人物贴图。
 */
public final class NpcClothingDetailsModel extends HumanoidModel<NpcRenderState> {
    private final NpcModel parent;
    private final ModelPart leftCuff;
    private final ModelPart rightCuff;
    private final ModelPart travelCape;
    private final ModelPart foldedHood;
    private final ModelPart leftShoulder;
    private final ModelPart rightShoulder;

    public NpcClothingDetailsModel(ModelPart root, NpcModel parent) {
        super(root);
        this.parent = parent;
        leftCuff = leftArm.getChild("work_cuff");
        rightCuff = rightArm.getChild("work_cuff");
        travelCape = body.getChild("travel_cape");
        foldedHood = body.getChild("folded_hood");
        leftShoulder = leftArm.getChild("travel_shoulder");
        rightShoulder = rightArm.getChild("travel_shoulder");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        var head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition rightArm = root.addOrReplaceChild("right_arm", CubeListBuilder.create(),
                PartPose.offset(-5.0F, 2.0F, 0.0F));
        PartDefinition leftArm = root.addOrReplaceChild("left_arm", CubeListBuilder.create(),
                PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));

        // 环状折边略高于原袖面，保留原来的袖长和手部；随手臂而非躯干运动。
        rightArm.addOrReplaceChild("work_cuff", CubeListBuilder.create().texOffs(64, 54)
                .addBox(-2.0F, 7.75F, -2.0F, 3.0F, 1.0F, 4.0F,
                        new CubeDeformation(0.35F)), PartPose.ZERO);
        leftArm.addOrReplaceChild("work_cuff", CubeListBuilder.create().texOffs(64, 54)
                .addBox(-1.0F, 7.75F, -2.0F, 3.0F, 1.0F, 4.0F,
                        new CubeDeformation(0.35F)), PartPose.ZERO);

        // 背片与左右前片保留领口开口；短下摆止于上胸，不干涉腰带、长裙或骑乘。
        body.addOrReplaceChild("travel_cape", CubeListBuilder.create()
                .texOffs(80, 0)
                .addBox(-4.35F, 0.55F, 2.62F, 8.70F, 5.30F, 0.65F)
                .texOffs(64, 0)
                .addBox(-4.40F, 0.15F, -2.73F, 3.45F, 3.30F, 0.55F)
                .texOffs(64, 0)
                .addBox(0.95F, 0.15F, -2.73F, 3.45F, 3.30F, 0.55F)
                .texOffs(64, 0)
                .addBox(-.95F, 1.65F, -2.73F, 1.90F, 1.80F, .55F)
                .texOffs(80, 0)
                .addBox(-4.4F, 0, -2.73F, 1.9F, .20F, 6.0F)
                .texOffs(80, 0)
                .addBox(2.5F, 0, -2.73F, 1.9F, .20F, 6.0F), PartPose.ZERO);

        // 兜帽落在后背，头部与脸型完全沿用原模型，不使用旧的宽面纱几何。
        body.addOrReplaceChild("folded_hood", CubeListBuilder.create()
                .texOffs(96, 0)
                .addBox(-2.60F, 0.20F, 3.15F, 5.20F, 3.00F, .65F)
                .texOffs(96, 10)
                .addBox(-2.10F, 0.05F, 2.70F, 4.20F, .65F, 1.0F)
                .texOffs(112, 0)
                .addBox(-1.40F, 2.90F, 3.18F, 2.80F, 1.60F, 0.70F), PartPose.ZERO);

        rightArm.addOrReplaceChild("travel_shoulder", CubeListBuilder.create().texOffs(80, 54)
                .addBox(-2.0F, -2.0F, -2.0F, 3.0F, 1.25F, 4.0F,
                        new CubeDeformation(0.34F)), PartPose.ZERO);
        leftArm.addOrReplaceChild("travel_shoulder", CubeListBuilder.create().texOffs(80, 54)
                .addBox(-1.0F, -2.0F, -2.0F, 3.0F, 1.25F, 4.0F,
                        new CubeDeformation(0.34F)), PartPose.ZERO);

        return LayerDefinition.create(mesh, 128, 64);
    }

    boolean copyAndConfigure(NpcModel parent, NpcProfession profession, boolean chestEquipped,
                             boolean headEquipped, boolean identityMantle) {
        boolean workCuffs = !chestEquipped && switch (profession) {
            case WORKER, FARMER, BLACKSMITH, COURIER -> true;
            default -> false;
        };
        boolean travelClothes = profession == NpcProfession.COURIER && !chestEquipped && !identityMantle;
        // 模型实例跨实体复用，每次显式设置全部可见性，避免前一个人的配件留到后一个人。
        leftCuff.visible = rightCuff.visible = workCuffs;
        travelCape.visible = leftShoulder.visible = rightShoulder.visible = travelClothes;
        foldedHood.visible = travelClothes && !headEquipped;
        boolean visible = workCuffs || travelClothes;
        // 只在有部件要画时复制变换；普通居民和军事职业不额外提交空渲染。
        if (visible) parent.copyPoseTo(this);
        return visible;
    }
    @Override public void setupAnim(NpcRenderState state) {
        parent.setupAnim(state);
        var status = state.socialStatus;
        boolean mantle = status == com.jgeted.sagadyssey.npc.identity.NpcSocialStatus.LORD
                || status == com.jgeted.sagadyssey.npc.identity.NpcSocialStatus.DUKE || status == com.jgeted.sagadyssey.npc.identity.NpcSocialStatus.KING;
        copyAndConfigure(parent, state.profession, state.chestArmored, state.helmeted, mantle);
    }

}
