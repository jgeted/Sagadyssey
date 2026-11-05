package com.jgeted.sagadyssey.npc.entity;

import com.jgeted.sagadyssey.npc.ai.AiMutex;
import com.jgeted.sagadyssey.npc.appearance.NpcFaceVariant;
import com.jgeted.sagadyssey.npc.appearance.NpcGender;
import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.faction.Faction;
import com.jgeted.sagadyssey.npc.faction.FactionAttachments;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.faction.IFactionInteractable;
import com.jgeted.sagadyssey.npc.faction.NpcFaction;
import com.jgeted.sagadyssey.npc.faction.StandingLevel;
import com.jgeted.sagadyssey.npc.identity.NpcIdentity;
import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.npc.identity.NpcSocialStatus;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.character.NpcCharacterIdentityMigration;
import com.jgeted.sagadyssey.npc.inventory.NpcInventoryMigration;
import com.jgeted.sagadyssey.npc.inventory.NpcPersonalInventory;
import com.jgeted.sagadyssey.npc.handover.NpcInteractionPolicy;
import com.jgeted.sagadyssey.npc.network.NpcInteractionPacket;
import com.jgeted.sagadyssey.npc.network.HandoverPromptPayload;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.profession.NpcMerchantRegion;
import com.jgeted.sagadyssey.npc.trade.NpcTradeOffer;
import com.jgeted.sagadyssey.npc.trade.NpcTradeRegistry;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Sagadyssey NPC 基类。
 * 包含属性系统、主人归属、NBT 持久化。
 *
 * v0.1 属性：
 *   HP / MaxHp → 原版 Attribute 系统，自动同步客户端
 *   Attack → 原版 Attribute 系统
 *   Speed → 原版 Attribute 系统
 *   Armor → 原版 Attribute 系统
 *   Lvl / Exp / Kills / Moral / Cost / OwnerUUID → 自定义 NBT
 */
public class NpcBase extends PathfinderMob implements IFactionInteractable {

    public static final String WORK_OUTPUT_OWNER_TAG = "SagadysseyWorkOutputOwner";

    private static final EntityDataAccessor<Integer> DATA_PROFESSION =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SOCIAL_CLASS =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_OFFICE =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_GENDER =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_FACE_VARIANT =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ACTION_DURATION =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ACTION_SEQUENCE =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LOGISTICS_STAGE =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_LOGISTICS_ITEM =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_LOGISTICS_DELIVERED =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LOGISTICS_REQUESTED =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LOGISTICS_CARGO =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.INT);
    /** 只同步账号 UUID 用于客户端隐藏私人指挥反馈；权限仍全部由服务端校验。 */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER_UUID =
            SynchedEntityData.defineId(NpcBase.class, EntityDataSerializers.OPTIONAL_UUID);

    // === 自定义属性（NBT 持久化） ===
    private String customName = "NPC";
    private NpcProfession profession = NpcProfession.NONE;
    private NpcSocialClass socialClass = NpcSocialClass.COMMONER;
    private NpcOffice office = NpcOffice.NONE;
    private NpcGender gender = NpcGender.MALE;
    private NpcFaceVariant faceVariant = NpcFaceVariant.YOUNG;

    /** 动作是瞬时表现，不写入存档。服务端结束 tick 仅负责复位同步状态。 */
    private int serverActionEndTick = -1;
    /** 仅统计服务端动作启动次数，供验收“一个生产批次只播放一次动作”。 */
    private int actionStartCount;
    private int observedActionSequence = -1;
    private int clientActionStartTick = 0;

    /** NPC 等级，1-100 */
    // 不再存储——由 experience 计算得出

    /** 当前经验值 */
    private int experience = 0;

    /** 商人交易随机池种子（出生时随机，持久化） */
    private int merchantTradeSeed = 0;

    /** 该 NPC 当前显示的交易列表（已固定数量，NBT 持久化） */
    private final List<NpcTradeOffer> activeTrades = new ArrayList<>();

    /** 该 NPC 已解锁的最高交易等级 */
    private int unlockedTradeLevel = 0;

    /** 诗人跟随计时器（tick），每 3600 tick（3 分钟）加经验 */
    private int bardFollowTicks = 0;

    /** 商人加价率 0.20-0.50（仅 TRADER 使用） */
    private double merchantMarkupRate = 0.30;

    /** 商人每半个游戏日轮换货品；地区变化也会立即重算价格。 */
    private long merchantRestockAt = -1L;
    private NpcMerchantRegion merchantRegion = NpcMerchantRegion.PLAINS;

    /** 击杀计数（累积，不重置） */
    private int kills = 0;

    /** 士气 0-100；分档影响伤害和低血撤退阈值。 */
    private int moral = 50;
    private static final ResourceLocation MORALE_DAMAGE_ID =
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "morale_damage");

    /** 招募费用（绿宝石数量） */
    private int recruitmentCost = 3;

    /** 当前行为指令 */
    private NpcCommand command = NpcCommand.IDLE;

    /** 农民务农模式（仅 FARMER 使用） */
    private FarmMode farmMode = FarmMode.AUTO;

    /** 框选中的工作范围第一角（未完成） */
    private BlockPos workZoneCorner1 = null;
    /** 工作范围最小角 */
    private BlockPos workZoneMin = null;
    /** 工作范围最大角 */
    private BlockPos workZoneMax = null;

    /** 阵营（新系统：基于 FactionRegistry 的 Faction 引用） */
    private Faction faction = FactionRegistry.get("sagadyssey:wilderness");

    /** NPC 等级类型（普通/精英/Boss），影响击杀声望惩罚 */
    private IFactionInteractable.NpcTier npcTier = IFactionInteractable.NpcTier.NORMAL;

    /** 主人 UUID，null 表示未被招募 */
    @Nullable
    private UUID ownerUUID = null;

    /** 社会人物 ID；与登录账号 UUID 分离，防止永久死亡后的新角色继承旧 NPC。 */
    @Nullable
    private UUID ownerCharacterId = null;

    /** 永久人物 ID；实体 UUID 只是当前身体的定位符。 */
    @Nullable
    private UUID characterId = null;

    /** 机构居民所属聚落；用于仓库授权和地方经济，不等同于玩家主人。 */
    @Nullable
    private UUID homeSettlementId = null;

    /** 招募前的原阵营 ID（解散时恢复），null 表示未招募或已解散 */
    @Nullable
    private String originalFaction = null;

    /** 绑定的坐骑 UUID */
    @Nullable
    private UUID mountUUID = null;

    /** 物流 Goal 提供的临时移动点；只用于骑乘驱动，不持久化。 */
    @Nullable
    private BlockPos logisticsMoveTarget = null;

    /** 服务端只读诊断快照；不参与决策，也不写入存档。 */
    private String workActivity = "尚未开始";
    private String workActivityDetail = "";
    @Nullable private BlockPos workActivityTarget = null;
    private long workActivityUpdatedAt = -1L;
    /** 只用于轮换当面话题，不属于需要持久化的世界真相。 */
    private int conversationTurn;

    /** 巡逻 Goal 提供的临时移动点；让骑乘中的守卫也能沿驻地路线移动。 */
    @Nullable
    private BlockPos patrolMoveTarget = null;

    /** 绑定的坐骑是否带有鞍（缓存，用于快速判断） */
    private boolean mountSaddled = false;

    /** 骑马攻击冷却（tick），0 表示可以攻击 */
    private int mountedAttackCooldown = 0;

    /** 战士冲锋是短时爆发；重甲兵的坚守位置由 STAY 指令记录。 */
    private static final ResourceLocation WARRIOR_CHARGE_DAMAGE_ID =
            ResourceLocation.fromNamespaceAndPath(Sagadyssey.MOD_ID, "warrior_charge_damage");
    private int warriorChargeTicks = 0;
    private int warriorChargeCooldown = 0;
    private int heavyBraceCooldown = 0;
    @Nullable
    private BlockPos guardAnchor = null;
    @Nullable
    private BlockPos patrolCenter = null;
    private int patrolRadius = 0;

    /** 牵马步行模式：true 时禁止自动上马 */
    private boolean leadMountMode = false;

    /** 待上马标记：true 时 MountGoal 会导航 NPC 走向坐骑并骑上去 */
    private boolean pendingMount = false;

    /** AI mutex 位标记集合，防止多个 AI goal 同时操控 NPC */
    private final Set<AiMutex> activeMutexes = EnumSet.noneOf(AiMutex.class);

    /** 敌对目标候选（由 faction 事件/hurt 写入，由 TargetSelector 消费） */
    @Nullable
    private LivingEntity hostileTargetCandidate = null;

    /** 最近一次受到有效伤害的服务端游戏时间；用于让休息行为等待环境重新安全。 */
    private long lastDamageGameTime = Long.MIN_VALUE;

    /** 主人最近的攻击者/攻击目标，由服务端伤害事件写入，解决多人战斗同步不稳定。 */
    @Nullable
    private LivingEntity ownerDefenseTargetCandidate = null;
    private long ownerDefenseTargetUntil = 0L;
    @Nullable
    private LivingEntity ownerAssistTargetCandidate = null;
    private long ownerAssistTargetUntil = 0L;

    /** 战斗目标选择 goal 引用（主人变更时刷新目标源） */
    @Nullable
    private com.jgeted.sagadyssey.npc.ai.NpcCombatGoal combatGoal = null;

    /** 近战攻击 goal 引用（用于武器切换时动态替换） */
    @Nullable
    private MeleeAttackGoal meleeAttackGoal = null;

    /** 远程攻击 goal（弓手模式时创建，近战模式时为 null） */
    @Nullable
    private com.jgeted.sagadyssey.npc.ai.NpcRangedAttackGoal rangedGoal = null;

    /** 上次检测的手持武器（用于变化检测） */
    private ItemStack lastCheckedWeapon = ItemStack.EMPTY;

    /** 当前是否处于远程模式 */
    private boolean isRangedMode = false;

    /** 与玩家一致的 36 格随身物品栏。 */
    private final NpcPersonalInventory equipmentInventory = new NpcPersonalInventory();

    /** 运输订单专用货舱，与日常装备隔离，防止同类物品被错误计入订单。 */
    private final SimpleContainer transportInventory = new SimpleContainer(27);

    /** 只保存异常旧档迁移时无法装入 36 格背包的物品，不参与日常玩法。 */
    private final List<ItemStack> inventoryMigrationOverflow = new ArrayList<>();

    // === 构造函数 ===

    public NpcBase(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        if (this.getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanOpenDoors(true);
        }
        if (!level.isClientSide) {
            this.gender = this.random.nextBoolean() ? NpcGender.MALE : NpcGender.FEMALE;
            NpcFaceVariant[] faces = NpcFaceVariant.values();
            this.faceVariant = faces[this.random.nextInt(faces.length)];
            this.entityData.set(DATA_GENDER, this.gender.ordinal());
            this.entityData.set(DATA_FACE_VARIANT, this.faceVariant.ordinal());
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PROFESSION, NpcProfession.NONE.ordinal());
        builder.define(DATA_SOCIAL_CLASS, NpcSocialClass.COMMONER.ordinal());
        builder.define(DATA_OFFICE, NpcOffice.NONE.ordinal());
        builder.define(DATA_GENDER, NpcGender.MALE.ordinal());
        builder.define(DATA_FACE_VARIANT, NpcFaceVariant.YOUNG.ordinal());
        builder.define(DATA_ACTION, NpcAction.NONE.ordinal());
        builder.define(DATA_ACTION_DURATION, 0);
        builder.define(DATA_ACTION_SEQUENCE, 0);
        builder.define(DATA_LOGISTICS_STAGE, 0);
        builder.define(DATA_LOGISTICS_ITEM, "");
        builder.define(DATA_LOGISTICS_DELIVERED, 0);
        builder.define(DATA_LOGISTICS_REQUESTED, 0);
        builder.define(DATA_LOGISTICS_CARGO, 0);
        builder.define(DATA_OWNER_UUID, Optional.empty());
    }

    // === Getter / Setter ===

    public String getNpcName() { return customName; }
    public void setNpcName(String name) {
        this.customName = name == null || name.isBlank() ? "NPC" : name;
        syncVisibleName();
    }

    public NpcGender getGender() {
        if (this.level().isClientSide) {
            int ordinal = this.entityData.get(DATA_GENDER);
            NpcGender[] values = NpcGender.values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NpcGender.MALE;
        }
        return gender;
    }

    public NpcFaceVariant getFaceVariant() {
        if (this.level().isClientSide) {
            int ordinal = this.entityData.get(DATA_FACE_VARIANT);
            NpcFaceVariant[] values = NpcFaceVariant.values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NpcFaceVariant.YOUNG;
        }
        return faceVariant;
    }

    /** 外观变化不会改变碰撞箱、属性、职业、身份或装备。 */
    public void setAppearance(NpcGender gender, NpcFaceVariant faceVariant) {
        this.gender = gender == null ? NpcGender.MALE : gender;
        this.faceVariant = faceVariant == null ? NpcFaceVariant.YOUNG : faceVariant;
        this.entityData.set(DATA_GENDER, this.gender.ordinal());
        this.entityData.set(DATA_FACE_VARIANT, this.faceVariant.ordinal());
    }

    public NpcProfession getProfession() {
        if (this.level().isClientSide) {
            int ordinal = this.entityData.get(DATA_PROFESSION);
            NpcProfession[] values = NpcProfession.values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NpcProfession.NONE;
        }
        return profession;
    }

    public void setProfession(NpcProfession profession) {
        boolean wasNone = (this.profession == NpcProfession.NONE);
        this.profession = profession;
        this.entityData.set(DATA_PROFESSION, profession.ordinal());
        if (profession != NpcProfession.NONE) {
            this.customName = profession.getDisplayName();
        }
        this.recruitmentCost = profession.getRecruitmentCost();

        if (profession == NpcProfession.TRADER && !this.level().isClientSide) {
            if (wasNone) this.merchantTradeSeed = this.random.nextInt();
            this.merchantRegion = NpcMerchantRegion.detect(this.level(), this.blockPosition());
            this.merchantRestockAt = this.level().getGameTime() + 12000L;
        }

        if (wasNone && profession != NpcProfession.NONE) {
            applyInitialEquipment(profession);
        }

        // 应用职业基础属性
        this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(profession.getMaxHp());
        this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(profession.getAttackDamage());
        this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(profession.getSpeed());
        this.getAttribute(Attributes.ARMOR).setBaseValue(profession.getArmor());
        this.getAttribute(Attributes.KNOCKBACK_RESISTANCE)
                .setBaseValue(profession == NpcProfession.HEAVY ? 0.65D : 0.0D);

        if (this.getHealth() > this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
        }

        this.experience = 0;  // 转职重置经验

        // 重置交易列表，重新抽 Lv1
        this.activeTrades.clear();
        this.unlockedTradeLevel = 0;
        if (!this.level().isClientSide && profession != NpcProfession.NONE) {
            this.merchantMarkupRate = 0.20 + this.random.nextDouble() * 0.30;
            unlockNextTradeLevel();
        }
        syncVisibleName();
    }

    private void syncVisibleName() {
        String role = profession == NpcProfession.NONE ? "" : profession.getDisplayName();
        String visible = role.isBlank() || customName.equals(role)
                ? customName : customName + " · " + role;
        super.setCustomName(Component.literal(visible));
        super.setCustomNameVisible(true);
    }

    public NpcSocialClass getSocialClass() {
        if (this.level().isClientSide) {
            int ordinal = this.entityData.get(DATA_SOCIAL_CLASS);
            NpcSocialClass[] values = NpcSocialClass.values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NpcSocialClass.COMMONER;
        }
        return socialClass;
    }

    public NpcOffice getOffice() {
        if (this.level().isClientSide) {
            int ordinal = this.entityData.get(DATA_OFFICE);
            NpcOffice[] values = NpcOffice.values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NpcOffice.NONE;
        }
        return office;
    }

    /** 供旧存档、视觉模型和旧开发命令使用的组合表现档位。 */
    public NpcSocialStatus getSocialStatus() {
        return NpcIdentity.presentation(getSocialClass(), getOffice());
    }

    /** 旧七级身份简写会被拆解为真实存储的阶层与职务。 */
    public void setSocialStatus(NpcSocialStatus socialStatus) {
        NpcSocialStatus resolved = socialStatus == null ? NpcSocialStatus.COMMONER : socialStatus;
        setIdentity(resolved.defaultSocialClass(), resolved.defaultOffice());
    }

    /** 身份变更不重置职业经验、背包或装备。 */
    public void setIdentity(NpcSocialClass socialClass, NpcOffice office) {
        this.socialClass = socialClass == null ? NpcSocialClass.COMMONER : socialClass;
        this.office = office == null ? NpcOffice.NONE : office;
        this.entityData.set(DATA_SOCIAL_CLASS, this.socialClass.ordinal());
        this.entityData.set(DATA_OFFICE, this.office.ordinal());
    }

    public Component getIdentityTitle() {
        String factionId = faction != null ? faction.id() : "sagadyssey:wilderness";
        return NpcIdentity.title(getSocialClass(), getOffice(), factionId, getGender());
    }

    public void playSocialGreeting() {
        playAction(NpcIdentity.greetingAction(getSocialClass(), getOffice()), 30);
    }

    /** 在服务端启动一个短时职业动作，并通过 SynchedEntityData 通知所有客户端。 */
    public void playAction(NpcAction action, int durationTicks) {
        if (this.level().isClientSide || action == null || action == NpcAction.NONE) return;
        int duration = Math.max(1, durationTicks);
        this.entityData.set(DATA_ACTION, action.ordinal());
        this.entityData.set(DATA_ACTION_DURATION, duration);
        this.entityData.set(DATA_ACTION_SEQUENCE, this.entityData.get(DATA_ACTION_SEQUENCE) + 1);
        this.actionStartCount++;
        this.serverActionEndTick = this.tickCount + duration;
    }

    public int getActionStartCount() {
        return actionStartCount;
    }

    public NpcAction getVisualAction() {
        int ordinal = this.entityData.get(DATA_ACTION);
        NpcAction[] actions = NpcAction.values();
        return ordinal >= 0 && ordinal < actions.length ? actions[ordinal] : NpcAction.NONE;
    }

    /** 客户端动作进度，范围为 0-1；同一动作重复播放时由 sequence 强制重新计时。 */
    public float getActionProgress(float partialTick) {
        int sequence = this.entityData.get(DATA_ACTION_SEQUENCE);
        if (sequence != this.observedActionSequence) {
            this.observedActionSequence = sequence;
            this.clientActionStartTick = this.tickCount;
        }
        int duration = this.entityData.get(DATA_ACTION_DURATION);
        if (duration <= 0 || getVisualAction() == NpcAction.NONE) return 0.0F;
        float progress = (this.tickCount - this.clientActionStartTick + partialTick) / duration;
        return Math.max(0.0F, Math.min(1.0F, progress));
    }

    public boolean hasActiveAction() {
        return getVisualAction() != NpcAction.NONE;
    }

    /** 同步正在执行的物流摘要。stage: 0=无, 1=去取货, 2=去交付, 3=护送。 */
    public void syncLogisticsStatus(int stage, String itemId, int delivered, int requested, int cargo) {
        if (this.level().isClientSide) return;
        this.entityData.set(DATA_LOGISTICS_STAGE, Math.max(0, Math.min(3, stage)));
        String safeItem = itemId == null ? "" : itemId;
        this.entityData.set(DATA_LOGISTICS_ITEM,
                safeItem.length() > 64 ? safeItem.substring(0, 64) : safeItem);
        this.entityData.set(DATA_LOGISTICS_DELIVERED, Math.max(0, delivered));
        this.entityData.set(DATA_LOGISTICS_REQUESTED, Math.max(0, requested));
        this.entityData.set(DATA_LOGISTICS_CARGO, Math.max(0, cargo));
    }

    public void clearLogisticsStatus() {
        syncLogisticsStatus(0, "", 0, 0, 0);
    }

    public int getLogisticsStage() { return this.entityData.get(DATA_LOGISTICS_STAGE); }
    public String getLogisticsItem() { return this.entityData.get(DATA_LOGISTICS_ITEM); }
    public int getLogisticsDelivered() { return this.entityData.get(DATA_LOGISTICS_DELIVERED); }
    public int getLogisticsRequested() { return this.entityData.get(DATA_LOGISTICS_REQUESTED); }
    public int getLogisticsCargo() { return this.entityData.get(DATA_LOGISTICS_CARGO); }
    public Optional<UUID> getSyncedOwnerUUID() { return this.entityData.get(DATA_OWNER_UUID); }

    /** 根据经验计算当前等级（1-4） */
    public int getNpcLevel() {
        if (experience < 200) return 1;
        if (experience < 600) return 2;
        if (experience < 1400) return 3;
        return 4;
    }

    /** 获取等级称号 */
    public String getLevelTitle() {
        return switch (getNpcLevel()) {
            case 1 -> "学徒";
            case 2 -> "熟练";
            case 3 -> "专家";
            case 4 -> "大师";
            default -> "学徒";
        };
    }

    public int getExperience() { return experience; }
    public void setExperience(int exp) { this.experience = Math.max(0, exp); }

    public int getMerchantTradeSeed() { return merchantTradeSeed; }
    public void setMerchantTradeSeed(int seed) { this.merchantTradeSeed = seed; }

    public List<NpcTradeOffer> getActiveTrades() { return Collections.unmodifiableList(activeTrades); }
    public int getUnlockedTradeLevel() { return unlockedTradeLevel; }
    public double getMerchantMarkupRate() { return merchantMarkupRate; }

    /** 初始化/升级时调用：从下一级池抽交易并固定数量 */
    public void unlockNextTradeLevel() {
        if (this.level().isClientSide) return;
        int nextLevel = unlockedTradeLevel + 1;
        if (nextLevel > 4 || getNpcLevel() < nextLevel) return;

        long seed = this.getCharacterId().getLeastSignificantBits() ^ ((long) merchantTradeSeed << 32);
        List<NpcTradeOffer> picked;

        if (this.profession == NpcProfession.TRADER) {
            int count = NpcTradeRegistry.PICK_COUNTS.get(NpcProfession.TRADER)[nextLevel - 1];
            List<NpcTradeOffer> pool = NpcTradeRegistry.getMerchantPool(nextLevel);
            Random rand = new Random(seed + nextLevel * 31L);
            // player 阵营 NPC 与主人交易时不加价（ignoreReputation=true）
            boolean isPlayerFaction = this.faction != null
                    && "sagadyssey:player".equals(this.faction.id());
            // Lv4 固定交易不加价
            if (nextLevel < 4) {
                List<NpcTradeOffer> markedUp = new ArrayList<>();
                for (NpcTradeOffer t : pool) {
                    markedUp.add(NpcTradeRegistry.applyMerchantMarkup(t, this.merchantMarkupRate, isPlayerFaction));
                }
                Collections.shuffle(markedUp, rand);
                picked = markedUp.subList(0, Math.min(count, markedUp.size()))
                        .stream().map(t -> t.resolve(rand))
                        .map(t -> NpcTradeRegistry.applyRegionalPricing(t, merchantRegion, false)).toList();
            } else {
                List<NpcTradeOffer> copy = new ArrayList<>(pool);
                Collections.shuffle(copy, rand);
                picked = copy.subList(0, Math.min(count, copy.size()))
                        .stream().map(t -> t.resolve(rand))
                        .map(t -> NpcTradeRegistry.applyRegionalPricing(t, merchantRegion, false)).toList();
            }
        } else {
            int[] counts = NpcTradeRegistry.PICK_COUNTS.getOrDefault(profession, new int[]{0, 0, 0, 0});
            int count = counts[nextLevel - 1];
            picked = NpcTradeRegistry.pickRandom(profession, nextLevel, seed, count, this.level().registryAccess());
        }

        activeTrades.addAll(picked);
        unlockedTradeLevel = nextLevel;
    }

    /** 添加经验。满级（4）时不执行。返回是否升级了 */
    public boolean addExperience(int amount) {
        if (amount <= 0) return false;
        int oldLevel = getNpcLevel();
        if (oldLevel >= 4) return false;
        this.experience += amount;
        int newLevel = getNpcLevel();
        if (newLevel > oldLevel) {
            applyLevelUpAttributes(newLevel);
            unlockNextTradeLevel();  // 升级解锁新交易
            return true;
        }
        return false;
    }

    @Override
    public void tick() {
        if(!level().isClientSide&&getServer()!=null){
            com.jgeted.sagadyssey.social.growth.SettlementSafetyRuntime.maintain(this);
            com.jgeted.sagadyssey.npc.brain.command.NpcDutyTask.emergency(this);
            com.jgeted.sagadyssey.social.combat.NpcCombatRuntime.maintain(this);
        }
        if (!level().isClientSide && getServer()!=null && tickCount%20==0)
            com.jgeted.sagadyssey.npc.brain.command.NpcCommandService.maintain(this);
        super.tick();
        if (this.level().isClientSide) return;

        if (this.serverActionEndTick >= 0 && this.tickCount >= this.serverActionEndTick) {
            this.entityData.set(DATA_ACTION, NpcAction.NONE.ordinal());
            this.entityData.set(DATA_ACTION_DURATION, 0);
            this.entityData.set(DATA_ACTION_SEQUENCE, this.entityData.get(DATA_ACTION_SEQUENCE) + 1);
            this.serverActionEndTick = -1;
        }

        boolean plannedBehavior =
                com.jgeted.sagadyssey.npc.brain.execution.NpcBrainRuntime.isPlanned(this);
        if (!plannedBehavior) {
            tickAmbientProfessionAction();
            tickMerchantRestock();
        }
        tickCombatProfessionRole();
        tickMoraleRecovery();
        com.jgeted.sagadyssey.social.life.NpcLifeBehavior.tick(this);

        // === 武器变更检测（主手物品变化 → 切换近战/远程 AI） ===
        ItemStack currentHeld = getMainHandItem();
        if (!ItemStack.matches(currentHeld, lastCheckedWeapon)) {
            com.jgeted.sagadyssey.npc.ai.NpcWeaponSwapHandler.onWeaponChanged(this, currentHeld);
            lastCheckedWeapon = currentHeld.copy();
        }

        // === 弓箭手补箭自动切回远程（弹药放背包时不经过 setArrowSlot，需轮询兜底）===
        if (!this.isRangedMode && this.profession == NpcProfession.ARCHER
                && this.tickCount % 10 == 0) {
            if (hasRangedWeaponAvailable() && hasAmmoAvailable()) {
                ensureRangedMode();
            }
        }

        // === 自动上下马检测 ===
        if (!plannedBehavior
                && this.tickCount % SagadysseyConfig.MOUNT_CHECK_INTERVAL.get() == 0) {
            if (this.isPassenger()) {
                if (shouldDismount()) {
                    // 工作命令优先于停放坐骑。附近无栅栏时也必须下马，随后由牵马 AI
                    // 让坐骑跟随，避免生产职业因为找不到拴马点而永久卡在马上。
                    tetherHorse();
                    this.dismountToBind();
                    this.leadMountMode = true;
                }
            } else if (this.hasMount() && !this.leadMountMode) {
                Entity mount = getMount();
                if (mount != null && mount.isAlive() && this.distanceTo(mount) <= 4.0F) {
                    // 马被拴在栅栏上时不自动上马
                    boolean isTethered = mount instanceof Leashable l && l.isLeashed();
                    if (!isTethered && !shouldDismount()) {
                        if (mount instanceof AbstractHorse h && h.isSaddled()) {
                            untetherHorse();
                            this.leadMountMode = false;
                            this.startRiding(mount);
                        }
                    }
                }
            }
        }

        // === 喂马逻辑（服务端，每 100 tick ≈ 5 秒） ===
        if (!plannedBehavior && this.tickCount % 100 == 0 && this.hasMount()) {
            Entity mount = getMount();
            if (mount instanceof LivingEntity livingMount && livingMount.isAlive()) {
                float hpRatio = livingMount.getHealth() / livingMount.getMaxHealth();
                if (hpRatio < 0.7f) {
                    int foodSlot = findHorseFood();
                    if (foodSlot >= 0) {
                        ItemStack food = equipmentInventory.getItem(foodSlot);
                        livingMount.heal(getHorseFoodHealAmount(food));
                        food.shrink(1);
                    }
                }
            }
        }

        // === 牵马步行逻辑（服务端） ===
        // NPC 的牵马是 AI 行为驱动，不依赖原版拴绳系统，因此不检查 isLeashed()
        if (!plannedBehavior && this.hasMount() && !this.isPassenger()) {
            Entity mount = getMount();
            if (mount instanceof AbstractHorse horse && horse.isAlive()) {
                boolean npcMoving = this.getNavigation().isInProgress() || this.zza > 0;
                if (npcMoving) {
                    double dist = this.distanceTo(horse);
                    if (dist > 4.0 && this.tickCount % 20 == 0) {
                        horse.getNavigation().moveTo(this, 1.2);
                    } else if (dist <= 2.5 && horse.getNavigation().isInProgress()) {
                        horse.getNavigation().stop();
                    }
                } else {
                    if (horse.getNavigation().isInProgress()) {
                        horse.getNavigation().stop();
                    }
                }
            }
        }

        // === 骑马驱马：直接设置马的速度向量（绕过 travel() 的 zza 机制） ===
        // 原因：AbstractHorse.getControllingPassenger() 只认 Player，NPC 乘客不产生前进输入。
        // MoveControl.setWantedPosition() 只设 speed 不设 zza，travel() 得不到前进力。
        // 改为直接 setDeltaMovement 驱动，可靠且速度可控。
        if (plannedBehavior && this.isPassenger()
                && (this.getTarget() == null || !this.getTarget().isAlive())
                && this.getVehicle() instanceof AbstractHorse horse) {
            horse.setDeltaMovement(0, horse.getDeltaMovement().y, 0);
        }
        if (this.isPassenger() && (!plannedBehavior
                || this.getTarget() != null && this.getTarget().isAlive())) {
            Entity vehicle = this.getVehicle();
            if (vehicle instanceof AbstractHorse horse) {
                Vec3 moveTarget = null;

                // === 骑马战斗（先判断，后移动） ===
                boolean inAttackWindow = false;
                if (this.getTarget() != null && this.getTarget().isAlive()) {
                    // 近战能力：战士/重装，或弓箭手（弹药耗尽切近战兜底时也需骑马挥砍）
                    boolean meleeCapable = this.profession == NpcProfession.WARRIOR
                            || this.profession == NpcProfession.HEAVY
                            || this.profession == NpcProfession.ARCHER;

                    // 远程模式下射击由 NpcRangedAttackGoal 处理，这里不挥砍；近战兜底(!isRangedMode)才贴脸挥剑
                    if (meleeCapable && !this.isRangedMode) {
                        // 冷却递减
                        if (this.mountedAttackCooldown > 0) {
                            this.mountedAttackCooldown--;
                        }

                        double distToTarget = this.distanceTo(this.getTarget());
                        if (distToTarget <= 2.8 && this.mountedAttackCooldown <= 0) {
                            // 停止马 → 挥剑裸伤 → 冷却 12 tick（~0.6 秒）
                            horse.setDeltaMovement(0, horse.getDeltaMovement().y, 0);
                            DamageSource source = this.damageSources().mobAttack(this);
                            float dmg = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
                            this.getTarget().hurt(source, dmg);
                            this.swing(InteractionHand.MAIN_HAND);
                            playAction(this.profession == NpcProfession.HEAVY
                                    ? NpcAction.HEAVY_STRIKE : NpcAction.MELEE_SLASH, 12);
                            this.mountedAttackCooldown = 12;
                            inAttackWindow = true;
                        }
                    } else if (this.isRangedMode) {
                        // 骑马射箭：射击交给 NpcRangedAttackGoal，走位见下方移动段
                    } else {
                        // 非战斗职业：下马战斗
                        this.dismountToBind();
                    }
                } else {
                    this.mountedAttackCooldown = 0;
                }

                // === 移动目标计算（冷却期间不移动，让马站稳攻击） ===
                if (!inAttackWindow) {
                    // 目标优先级：攻击目标 > 物流路点 > 跟随主人 > 最近伤害源 > 导航目标
                    if (this.getTarget() != null) {
                        moveTarget = this.getTarget().position();
                    }
                    if (moveTarget == null && this.logisticsMoveTarget != null) {
                        moveTarget = Vec3.atBottomCenterOf(this.logisticsMoveTarget);
                    }
                    if (moveTarget == null && this.command == NpcCommand.PATROL
                            && this.patrolMoveTarget != null) {
                        moveTarget = Vec3.atBottomCenterOf(this.patrolMoveTarget);
                    }
                    if (moveTarget == null && this.command == NpcCommand.FOLLOW && this.ownerUUID != null) {
                        Player owner = this.getActiveOwner();
                        if (owner != null) moveTarget = owner.position();
                    }
                    if (moveTarget == null && this.getLastHurtMob() != null) {
                        moveTarget = this.getLastHurtMob().position();
                    }
                    if (moveTarget == null && this.getNavigation().isInProgress()) {
                        moveTarget = Vec3.atBottomCenterOf(this.getNavigation().getTargetPos());
                    }
                }

                if (moveTarget != null) {
                    double distSq = this.distanceToSqr(moveTarget);
                    // 移动方向：1=逼近, 0=保持距离停下, -1=后撤拉开
                    int moveDir;
                    if (this.getTarget() != null) {
                        if (this.isRangedMode) {
                            // 骑马弓箭手风筝：保持 8~14 格输出距离（可调）
                            if (distSq > 14.0 * 14.0) {
                                moveDir = 1;   // 太远，逼近
                            } else if (distSq < 8.0 * 8.0) {
                                moveDir = -1;  // 太近，后撤拉开
                            } else {
                                moveDir = 0;   // 射程带内，停下射击
                            }
                        } else {
                            // 近战贴身上去砍（1.5 格）——弓箭手弹药耗尽切近战兜底时也走这里
                            moveDir = distSq > 2.25 ? 1 : 0;
                        }
                    } else if (this.command == NpcCommand.FOLLOW) {
                        moveDir = distSq > 12.0 ? 1 : 0;
                    } else {
                        moveDir = distSq > 6.0 ? 1 : 0;
                    }
                    if (moveDir != 0) {
                        // 面向目标（后撤时也保持面向，边退边射）
                        Vec3 toTarget = moveTarget.subtract(this.position());
                        double horizDist = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
                        float yaw = (float) (Math.atan2(toTarget.z, toTarget.x) * 180.0 / Math.PI) - 90.0F;
                        horse.setYRot(yaw);
                        horse.yBodyRot = yaw;
                        horse.yHeadRot = yaw;

                        if (horizDist < 0.1) {
                            // 目标在正上/下方，水平不动
                            horse.setDeltaMovement(0, horse.getDeltaMovement().y, 0);
                        } else {
                            // 直接设置水平速度（马的属性速度 × 1.5 加成），moveDir=-1 时反向
                            float horseSpeed = (float) horse.getAttributeValue(Attributes.MOVEMENT_SPEED);
                            float moveSpeed = horseSpeed * 1.5F;
                            double dx = (toTarget.x / horizDist) * moveSpeed * moveDir;
                            double dz = (toTarget.z / horizDist) * moveSpeed * moveDir;

                            // Y 轴：游泳上浮 / 跳跃障碍 / 正常重力
                            double dy = horse.getDeltaMovement().y;
                            if (horse.isInWater()) {
                                dy = 0.15;
                                horse.setJumping(true);
                            } else if (horse.onGround() && horse.horizontalCollision && this.tickCount % 5 == 0) {
                                dy = 0.42;
                                horse.setJumping(true);
                            }

                            horse.setDeltaMovement(dx, dy, dz);
                        }
                    } else {
                        // 距离足够，停止（保留 Y 轴让重力处理）
                        horse.setDeltaMovement(0, horse.getDeltaMovement().y, 0);
                    }
                } else {
                    // 无目标，停止
                    horse.setDeltaMovement(0, horse.getDeltaMovement().y, 0);
                }

            }
        }

        // 诗人跟随计时器
        if (this.profession == NpcProfession.BARD
                && !plannedBehavior
                && this.command == NpcCommand.FOLLOW
                && getNpcLevel() < 4) {
            bardFollowTicks++;
            if (bardFollowTicks >= 3600) {
                bardFollowTicks = 0;
                addExperience(5);
            }
        }
    }

    /** 没有专属工作 Goal 的职业先使用克制的环境动作，不改变任何游戏结果。 */
    private void tickAmbientProfessionAction() {
        if (this.getTarget() != null || this.isPassenger() || this.hasActiveAction()) return;
        if (!this.getNavigation().isDone()) return;

        if (this.profession == NpcProfession.NONE
                && (this.tickCount + this.getId()) % 360 == 0) {
            playSocialGreeting();
        }
    }

    /** 商人不必处于 WORK 才会补货；静止且脱离战斗时每日检查一次。 */
    private void tickMerchantRestock() {
        if (this.profession != NpcProfession.TRADER || this.getTarget() != null || this.isPassenger()) return;
        if ((this.tickCount - this.getId()) % 100 != 0) return;
        restockMerchantIfNeeded();
    }

    public void restockMerchantIfNeeded() {
        if (this.level().isClientSide || this.profession != NpcProfession.TRADER) return;
        NpcMerchantRegion detected = NpcMerchantRegion.detect(this.level(), this.blockPosition());
        if (this.merchantRestockAt < 0L) this.merchantRestockAt = this.level().getGameTime() + 12000L;
        if (detected == this.merchantRegion && this.level().getGameTime() < this.merchantRestockAt) return;

        int levelsToRestore = Math.max(1, this.unlockedTradeLevel);
        this.merchantRegion = detected;
        this.merchantTradeSeed = this.random.nextInt();
        this.activeTrades.clear();
        this.unlockedTradeLevel = 0;
        for (int level = 0; level < levelsToRestore; level++) unlockNextTradeLevel();
        this.merchantRestockAt = this.level().getGameTime() + 12000L;
        if (!hasActiveAction()) playAction(NpcAction.TRADE_PRESENT, 40);
    }

    /** 战士自动短距冲锋；重甲兵在 STAY 时进入坚守并限制追击范围。 */
    private void tickCombatProfessionRole() {
        if (warriorChargeCooldown > 0) warriorChargeCooldown--;
        if (heavyBraceCooldown > 0) heavyBraceCooldown--;

        LivingEntity target = this.getTarget();
        if (this.profession == NpcProfession.WARRIOR && target != null && target.isAlive()) {
            double distanceSq = this.distanceToSqr(target);
            if (warriorChargeTicks <= 0 && warriorChargeCooldown <= 0
                    && distanceSq >= 25.0D && distanceSq <= 196.0D && !this.isPassenger()) {
                warriorChargeTicks = 30;
                playAction(NpcAction.CHARGE, 30);
            }
            if (warriorChargeTicks > 0) {
                this.setSprinting(true);
                if (!this.isPassenger()) this.getNavigation().moveTo(target, 1.35D);
                warriorChargeTicks--;
                if (warriorChargeTicks <= 0) finishWarriorCharge();
            }
        } else {
            if (warriorChargeTicks > 0) finishWarriorCharge();
            else this.setSprinting(false);
        }

        if (this.profession == NpcProfession.HEAVY && this.command == NpcCommand.STAY) {
            if (guardAnchor == null) guardAnchor = this.blockPosition().immutable();
            if (target != null && target.isAlive()) {
                double dx = target.getX() - (guardAnchor.getX() + 0.5D);
                double dz = target.getZ() - (guardAnchor.getZ() + 0.5D);
                if (dx * dx + dz * dz > 100.0D) {
                    this.setTarget(null);
                } else if (heavyBraceCooldown <= 0) {
                    playAction(NpcAction.BRACE, 35);
                    heavyBraceCooldown = 80;
                }
            }
        }
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean charging = this.profession == NpcProfession.WARRIOR && warriorChargeTicks > 0;
        AttributeInstance attackDamage = charging ? this.getAttribute(Attributes.ATTACK_DAMAGE) : null;
        AttributeModifier chargeDamage = charging
                ? new AttributeModifier(WARRIOR_CHARGE_DAMAGE_ID, 1.5D + getNpcLevel() * 0.5D,
                    AttributeModifier.Operation.ADD_VALUE)
                : null;

        if (attackDamage != null && chargeDamage != null) {
            attackDamage.removeModifier(WARRIOR_CHARGE_DAMAGE_ID);
            attackDamage.addTransientModifier(chargeDamage);
        }

        boolean didHit;
        try {
            // 将冲锋加成并入同一次原版近战结算，避免第二次 hurt 被受伤无敌帧吞掉。
            didHit = super.doHurtTarget(target);
        } finally {
            if (attackDamage != null) attackDamage.removeModifier(WARRIOR_CHARGE_DAMAGE_ID);
        }

        if (didHit && !this.level().isClientSide) {
            if (charging) finishWarriorCharge();
            playAction(this.profession == NpcProfession.HEAVY
                    ? NpcAction.HEAVY_STRIKE : NpcAction.MELEE_SLASH, 12);
        }
        return didHit;
    }

    private void finishWarriorCharge() {
        warriorChargeTicks = 0;
        warriorChargeCooldown = 120;
        this.setSprinting(false);
    }

    /** 升级属性增长 */
    private void applyLevelUpAttributes(int newLevel) {
        NpcProfession prof = this.profession;
        double hpMult = 1.0 + (newLevel - 1) * 0.15;
        double atkMult = 1.0 + (newLevel - 1) * 0.10;
        double spdMult = 1.0 + (newLevel - 1) * 0.05;
        int armorBonus = (newLevel - 1);

        this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(prof.getMaxHp() * hpMult);
        this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(prof.getAttackDamage() * atkMult);
        this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(prof.getSpeed() * spdMult);
        this.getAttribute(Attributes.ARMOR).setBaseValue(prof.getArmor() + armorBonus);
        this.setHealth(this.getMaxHealth());
    }

    public int getKills() { return kills; }
    public void setKills(int kills) { this.kills = Math.max(0, kills); }
    /** 击杀数 +1 */
    public void addKill() {
        this.kills++;
        adjustMoral(5);
    }

    public int getMoral() { return moral; }
    public void setMoral(int moral) {
        this.moral = Math.max(0, Math.min(100, moral));
        refreshMoraleCombatModifier();
    }
    public void adjustMoral(int amount) { setMoral(this.moral + amount); }
    public NpcMoraleState getMoraleState() { return NpcMoraleState.fromValue(moral); }
    public double getMoraleDamageMultiplier() { return getMoraleState().damageMultiplier(); }

    private void refreshMoraleCombatModifier() {
        AttributeInstance attackDamage = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage == null) return;
        attackDamage.removeModifier(MORALE_DAMAGE_ID);
        double amount = getMoraleDamageMultiplier() - 1.0D;
        if (Math.abs(amount) > 0.0001D) {
            attackDamage.addTransientModifier(new AttributeModifier(
                    MORALE_DAMAGE_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** 没有持续鼓舞或打击时，士气每 30 秒向常态值 50 回归一点。 */
    private void tickMoraleRecovery() {
        if ((this.tickCount - this.getId()) % 600 != 0) return;
        if (moral > 50) adjustMoral(-1);
        else if (moral < 50) adjustMoral(1);
    }

    public int getRecruitmentCost() { return recruitmentCost; }
    public int getAdjustedRecruitmentCost() {
        return NpcIdentity.recruitmentCost(recruitmentCost, socialClass, office);
    }
    public boolean isSociallyRecruitable() {
        return NpcIdentity.isDirectlyRecruitable(socialClass, office);
    }
    public void setRecruitmentCost(int cost) { this.recruitmentCost = Math.max(1, cost); }

    public NpcCommand getCommand() { return command; }
    public void setCommand(NpcCommand command) {
        this.command = command;
        if (command == NpcCommand.STAY) this.guardAnchor = this.blockPosition().immutable();
    }

    @Nullable
    public BlockPos getGuardAnchor() { return guardAnchor; }

    @Nullable
    public UUID getHomeSettlementId() { return homeSettlementId; }

    public void setHomeSettlementId(@Nullable UUID settlementId) { this.homeSettlementId = settlementId; }

    public void setPatrol(BlockPos center, int radius) {
        this.patrolCenter = center.immutable();
        this.patrolRadius = Math.max(4, Math.min(48, radius));
    }

    @Nullable
    public BlockPos getPatrolCenter() { return patrolCenter; }

    public int getPatrolRadius() { return patrolRadius; }

    public NpcMerchantRegion getMerchantRegion() { return merchantRegion; }

    public long getMerchantRestockAt() { return merchantRestockAt; }

    public FarmMode getFarmMode() { return farmMode; }
    public void setFarmMode(FarmMode mode) { this.farmMode = mode; }

    /** 标记工作范围第一角（玩家脚下） */
    public void markWorkZoneCorner1(BlockPos pos) {
        this.workZoneCorner1 = pos.immutable();
    }

    /** 标记第二角，生成矩形范围 */
    public void markWorkZoneCorner2(BlockPos pos) {
        if (this.workZoneCorner1 == null) return;
        BlockPos a = this.workZoneCorner1;
        this.workZoneMin = new BlockPos(
                Math.min(a.getX(), pos.getX()), Math.min(a.getY(), pos.getY()), Math.min(a.getZ(), pos.getZ()));
        this.workZoneMax = new BlockPos(
                Math.max(a.getX(), pos.getX()), Math.max(a.getY(), pos.getY()), Math.max(a.getZ(), pos.getZ()));
        this.workZoneCorner1 = null;
    }

    public boolean hasWorkZone() {
        return this.workZoneMin != null && this.workZoneMax != null;
    }

    public void clearWorkZone() {
        this.workZoneCorner1 = null;
        this.workZoneMin = null;
        this.workZoneMax = null;
    }

    /** 直接设置工作范围（两个角自动归一化为 min/max） */
    public void setWorkZone(BlockPos a, BlockPos b) {
        this.workZoneMin = new BlockPos(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.workZoneMax = new BlockPos(
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.workZoneCorner1 = null;
    }

    /** 是否允许在该位置作业（未设范围则全允许） */
    public boolean isWorkAllowedAt(BlockPos pos) {
        return !hasWorkZone() || isInWorkZone(pos);
    }

    public BlockPos getWorkZoneMin() { return workZoneMin; }
    public BlockPos getWorkZoneMax() { return workZoneMax; }

    private boolean isInWorkZone(BlockPos pos) {
        return pos.getX() >= workZoneMin.getX() && pos.getX() <= workZoneMax.getX()
                && pos.getY() >= workZoneMin.getY() && pos.getY() <= workZoneMax.getY()
                && pos.getZ() >= workZoneMin.getZ() && pos.getZ() <= workZoneMax.getZ();
    }

    public Faction getFaction() { return faction; }
    public void setFaction(Faction faction) { this.faction = faction; }

    @Override
    public IFactionInteractable.NpcTier getNpcTier() { return npcTier; }
    public void setNpcTier(IFactionInteractable.NpcTier tier) { this.npcTier = tier; }

    /** 获取旧版 NpcFaction（向后兼容，基于阵营的敌对性判定） */
    public NpcFaction getLegacyFaction() {
        if (faction == null) return NpcFaction.NEUTRAL;
        return faction.canBeHostile() ? NpcFaction.HOSTILE : NpcFaction.NEUTRAL;
    }

    @Nullable
    public UUID getOwnerUUID() { return ownerUUID; }

    @Nullable
    public UUID getOwnerCharacterId() { return ownerCharacterId; }

    /** @deprecated compatibility projection; authoritative state is a service relationship. */
    @Deprecated
    public boolean isOwned() {
        if (level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            var services = com.jgeted.sagadyssey.social.service.ServiceRelationshipData
                    .get(serverLevel.getServer());
            if (services.hasHistory(getCharacterId())) {
                return !services.activeForAgent(getCharacterId(), serverLevel.getGameTime()).isEmpty();
            }
        }
        return ownerUUID != null;
    }

    public boolean isOwnedBy(UUID playerUUID) {
        Player player = this.level().getPlayerByUUID(playerUUID);
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            // 客户端只负责显示；所有权限判断最终仍由服务端再次验证。
            return this.level().isClientSide && ownerUUID != null && ownerUUID.equals(playerUUID);
        }
        UUID current = CharacterRegistry.currentId(serverPlayer);
        if (!serverPlayer.isAlive() || current == null) return false;
        bindLegacyOwnerToCurrent(serverPlayer);
        var services = com.jgeted.sagadyssey.social.service.ServiceRelationshipData
                .get(serverPlayer.getServer());
        if (!services.hasHistory(getCharacterId()) && ownerCharacterId != null) {
            services.ensureLegacyRecruitment(ownerCharacterId, getCharacterId(),
                    serverPlayer.serverLevel().getGameTime());
        }
        return services.hasActiveBetween(current, getCharacterId(),
                serverPlayer.serverLevel().getGameTime());
    }

    /** 返回仍在控制此 NPC 的在线玩家；上一世的账号不再被视作主人。 */
    @Nullable
    public Player getActiveOwner() {
        if (ownerUUID == null) return null;
        Player player = this.level().getPlayerByUUID(ownerUUID);
        return player != null && isOwnedBy(player.getUUID()) ? player : null;
    }

    /** 旧存档迁移：只允许把没有 Character ID 的 NPC 绑定到账号当前角色。 */
    public void bindLegacyOwnerToCurrent(net.minecraft.server.level.ServerPlayer player) {
        if (ownerCharacterId != null || ownerUUID == null || !ownerUUID.equals(player.getUUID())) return;
        if (CharacterRegistry.current(player) == null) return;
        ownerCharacterId = CharacterRegistry.legacyOwnerCharacter(player);
    }

    /** 招募前的原阵营 ID（解散时恢复），null 表示未招募或已解散 */
    @Nullable
    public String getOriginalFaction() { return originalFaction; }

    public void setOriginalFaction(String id) { this.originalFaction = id; }

    /**
     * 设置主人（招募时调用）。
     * 首次设置 owner 时自动保存原阵营并切换到 player 阵营。
     * 传入 null 时不处理阵营——阵营清除统一由 {@link #dismiss()} 负责。
     */
    public void setOwner(UUID playerUUID) {
        this.ownerUUID = playerUUID;
        this.entityData.set(DATA_OWNER_UUID, Optional.ofNullable(playerUUID));
        this.ownerCharacterId = null;
        if (playerUUID != null) {
            // 仅同步旧客户端字段；服务关系不改变籍贯、制度成员或阵营。
            if (combatGoal != null) combatGoal.refreshSources();
        }
    }

    /** 新招募入口：同时记录账号和当前社会人物。 */
    public void setOwner(net.minecraft.server.level.ServerPlayer player) {
        setOwner(player.getUUID());
        this.ownerCharacterId = CharacterRegistry.ensureCurrent(player).id();
    }

    /**
     * 解散 NPC：恢复原阵营、清除主人。
     * 原阵营被数据包删除时 fallback 到 wilderness。
     */
    public void dismiss() {
        if (this.originalFaction != null) {
            Faction original = FactionRegistry.get(this.originalFaction);
            this.faction = original != null ? original : FactionRegistry.get("sagadyssey:wilderness");
            this.originalFaction = null;
        }
        this.ownerUUID = null;
        this.entityData.set(DATA_OWNER_UUID, Optional.empty());
        this.ownerCharacterId = null;
    }

    /** Server-side compatibility projection from the authoritative relationship store. */
    public void syncLegacyServiceProjection(@Nullable UUID accountId, @Nullable UUID principalId) {
        this.ownerUUID = accountId;
        this.ownerCharacterId = principalId;
        this.entityData.set(DATA_OWNER_UUID, Optional.ofNullable(accountId));
        if (combatGoal != null) combatGoal.refreshSources();
    }

    /** Old recruitment changed faction; v2 restores social identity without ending the service. */
    public void restoreServiceIndependentFaction() {
        if (this.originalFaction == null) return;
        Faction original = FactionRegistry.get(this.originalFaction);
        this.faction = original != null ? original : FactionRegistry.get("sagadyssey:wilderness");
        this.originalFaction = null;
    }

    // === 坐骑绑定 ===

    /** 绑定坐骑 */
    public void bindMount(Entity mount) {
        if (mount == null || !mount.isAlive()) return;
        this.mountUUID = mount.getUUID();
        this.mountSaddled = mount instanceof AbstractHorse h && h.isSaddled();

        // 绑定坐骑时确保有栓绳
        if (!hasLeadInInventory()) {
            for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
                if (equipmentInventory.getItem(i).isEmpty()) {
                    equipmentInventory.setItem(i, new ItemStack(Items.LEAD));
                    break;
                }
            }
        }

        // 如果 NPC 已经在马旁边且马有鞍，立刻骑上去
        if (!this.level().isClientSide && this.distanceTo(mount) <= 3.0F) {
            if (mount instanceof AbstractHorse h && h.isSaddled()) {
                this.startRiding(mount);
            }
        }
    }

    /** 解绑坐骑 */
    public void unbindMount() {
        if (this.isPassenger()) this.dismountToBind();
        this.mountUUID = null;
        this.mountSaddled = false;
    }

    /** 获取绑定的坐骑实体 */
    @Nullable
    public Entity getMount() {
        if (mountUUID == null) return null;
        if (this.level() == null) return null;
        for (Entity e : this.level().getEntities(this, this.getBoundingBox().inflate(64))) {
            if (e.getUUID().equals(mountUUID) && e.isAlive()) return e;
        }
        return null;
    }

    /** 是否有绑定坐骑 */
    public boolean hasMount() { return mountUUID != null; }

    /** 骑上绑定的坐骑 */
    public void mountToBind() {
        if (this.isPassenger()) return;
        Entity mount = getMount();
        if (mount != null && mount.isAlive() && this.distanceTo(mount) <= 4.0F) {
            this.startRiding(mount);
        }
    }

    /** 从坐骑下来 */
    public void dismountToBind() {
        if (this.isPassenger()) {
            this.stopRiding();
        }
    }

    /** 设置牵马步行模式（禁止自动上马） */
    public void setLeadMountMode(boolean mode) { this.leadMountMode = mode; }
    public boolean isLeadMountMode() { return leadMountMode; }

    /** 待上马标记（供 MountGoal 和网络包使用） */
    public void setPendingMount() { this.pendingMount = true; }
    public void clearPendingMount() { this.pendingMount = false; }
    public boolean isPendingMount() { return pendingMount; }

    // === AI Mutex 管理 ===

    /**
     * 申请 mutex 位。所有位都空闲时申请成功并占用，否则返回 false。
     */
    public boolean requestMutex(AiMutex... mutexes) {
        for (AiMutex m : mutexes) {
            if (activeMutexes.contains(m)) {
                Sagadyssey.LOGGER.debug("[MUTEX] {} requestMutex {} DENIED (held: {})",
                        getNpcName(), Arrays.toString(mutexes), activeMutexes);
                return false;
            }
        }
        Collections.addAll(activeMutexes, mutexes);
        Sagadyssey.LOGGER.debug("[MUTEX] {} requestMutex {} GRANTED (held: {})",
                getNpcName(), Arrays.toString(mutexes), activeMutexes);
        return true;
    }

    /** 释放 mutex 位 */
    public void releaseMutex(AiMutex... mutexes) {
        for (AiMutex m : mutexes) activeMutexes.remove(m);
        Sagadyssey.LOGGER.debug("[MUTEX] {} releaseMutex {} (held: {})",
                getNpcName(), Arrays.toString(mutexes), activeMutexes);
    }

    // === 武器模式切换 ===

    /** 切换到近战模式：移除远程 goal，注册近战 goal，并把主手的弓换成背包里的剑/斧 */
    public void ensureMeleeMode() {
        if (this.level().isClientSide) return;
        if (!isRangedMode) return; // 已经是近战模式

        if (rangedGoal != null) {
            this.goalSelector.removeGoal(rangedGoal);
            rangedGoal = null;
        }
        if (meleeAttackGoal != null) {
            this.goalSelector.addGoal(1, meleeAttackGoal);
        }
        isRangedMode = false;

        // 自动换近战武器：主手仍是弓/弩时，若背包有剑/斧则交换
        // （弹药耗尽切近战兜底时不再拿弓贴脸）
        equipBestMeleeWeapon();
    }

    /** 切换到远程模式：移除近战 goal，注册远程 goal，并把主手武器换回弓/弩 */
    public void ensureRangedMode() {
        if (this.level().isClientSide) return;
        if (isRangedMode) return; // 已经是远程模式

        if (meleeAttackGoal != null) {
            this.goalSelector.removeGoal(meleeAttackGoal);
        }
        if (rangedGoal == null) {
            rangedGoal = new com.jgeted.sagadyssey.npc.ai.NpcRangedAttackGoal(this, 1.0D, 20, 16.0F);
        }
        this.goalSelector.addGoal(1, rangedGoal);
        isRangedMode = true;

        // 自动换远程武器：主手是剑/斧或空手时，从背包/弓槽找回弓
        // （补箭切回远程时重新拿弓）
        equipBestRangedWeapon();
    }

    // === 武器自动装备辅助 ===

    /** 是否是远程武器（弓/弩） */
    private static boolean isRangedWeaponItem(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem);
    }

    /** 是否是近战武器（剑/斧） */
    private static boolean isMeleeWeaponItem(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem);
    }

    /** 在 36 格随身物品栏中查找近战武器，返回槽位下标，无则 -1。 */
    private int findMeleeWeaponInBag() {
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            if (isMeleeWeaponItem(equipmentInventory.getItem(i))) return i;
        }
        return -1;
    }

    /** 在 36 格随身物品栏中查找远程武器，返回槽位下标，无则 -1。 */
    private int findRangedWeaponInBag() {
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            if (isRangedWeaponItem(equipmentInventory.getItem(i))) return i;
        }
        return -1;
    }

    /** 是否有远程武器可用（主/副手或随身物品栏）。 */
    private boolean hasRangedWeaponAvailable() {
        return isRangedWeaponItem(getMainHandItem())
                || isRangedWeaponItem(getOffhandItem())
                || findRangedWeaponInBag() >= 0;
    }

    /** 箭矢和玩家一样存放在普通物品栏，不存在 NPC 专用弹药槽。 */
    private boolean hasAmmoAvailable() {
        return equipmentInventory.contains(stack -> stack.getItem() instanceof ArrowItem);
    }

    /** 把物品放进背包：优先堆叠到同类物品，其次空位；放不下返回 false */
    public boolean addToBag(ItemStack stack) {
        if (stack.isEmpty()) return true;
        ItemStack remainder = equipmentInventory.insert(stack);
        stack.setCount(remainder.getCount());
        return remainder.isEmpty();
    }

    /** 仅在工作背包确实装不下时生成带来源标记的实体，之后可由本人安全回收。 */
    public void spawnWorkOutput(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemEntity item = this.spawnAtLocation(stack);
        if (item != null) item.getPersistentData().putUUID(WORK_OUTPUT_OWNER_TAG, getCharacterId());
    }

    public boolean hasBagItem(Item item, int count) {
        if (count <= 0) return true;
        int found = 0;
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            ItemStack stack = equipmentInventory.getItem(i);
            if (stack.is(item)) found += stack.getCount();
            if (found >= count) return true;
        }
        return false;
    }

    /** 原子式消耗：数量不足时不改动背包。 */
    public boolean consumeBagItem(Item item, int count) {
        return equipmentInventory.consume(stack -> stack.is(item), count);
    }

    public boolean hasBagSpaceFor(ItemStack incoming) {
        if (incoming.isEmpty()) return true;
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            ItemStack stack = equipmentInventory.getItem(i);
            if (stack.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(stack, incoming)
                    && stack.getCount() + incoming.getCount() <= stack.getMaxStackSize()) return true;
        }
        return false;
    }

    /** 同时模拟多份物品入包，避免逐项检查把同一个空槽重复计算。 */
    public boolean hasBagSpaceForAll(ItemStack... incoming) {
        ItemStack[] simulated = new ItemStack[equipmentInventory.getContainerSize()];
        for (int slot = 0; slot < simulated.length; slot++) {
            simulated[slot] = equipmentInventory.getItem(slot).copy();
        }
        for (ItemStack offered : incoming) {
            if (offered == null || offered.isEmpty()) continue;
            ItemStack remaining = offered.copy();
            for (ItemStack slot : simulated) {
                if (!remaining.isEmpty() && !slot.isEmpty()
                        && ItemStack.isSameItemSameComponents(slot, remaining)) {
                    int moved = Math.min(remaining.getCount(), slot.getMaxStackSize() - slot.getCount());
                    slot.grow(moved);
                    remaining.shrink(moved);
                }
            }
            for (int slot = 0; slot < simulated.length && !remaining.isEmpty(); slot++) {
                if (!simulated[slot].isEmpty()) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                simulated[slot] = remaining.copyWithCount(moved);
                remaining.shrink(moved);
            }
            if (!remaining.isEmpty()) return false;
        }
        return true;
    }

    /** 供生产/入库 AI 做背压判断；已装备物品不计入 36 格随身物品栏。 */
    public int getBagFreeSlots() {
        int free = 0;
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            if (equipmentInventory.getItem(i).isEmpty()) free++;
        }
        return free;
    }

    public int getBagUsedSlots() {
        return equipmentInventory.getContainerSize() - getBagFreeSlots();
    }

    /** 主手与背包某格交换物品（弓↔剑互换） */
    private void swapMainHandWithBag(int bagSlot) {
        ItemStack held = getMainHandItem();
        ItemStack bagItem = equipmentInventory.getItem(bagSlot).copy();
        equipmentInventory.setItem(bagSlot, held.copy());
        setItemSlot(EquipmentSlot.MAINHAND, bagItem);
    }

    /** 进入近战模式时：主手是弓就把剑/斧换上来（弓放回原剑格） */
    private void equipBestMeleeWeapon() {
        if (isRangedWeaponItem(getMainHandItem())) {
            int slot = findMeleeWeaponInBag();
            if (slot >= 0) {
                swapMainHandWithBag(slot);
            }
        }
    }

    /** 进入远程模式时：主手不是弓就从物品栏找回弓（近战武器放回物品栏）。 */
    private void equipBestRangedWeapon() {
        ItemStack held = getMainHandItem();
        if (isRangedWeaponItem(held)) return; // 已经拿着弓

        int bagSlot = findRangedWeaponInBag();
        if (bagSlot >= 0) {
            // 背包里有弓 → 主手与背包格交换
            swapMainHandWithBag(bagSlot);
            return;
        }
    }

    // === 敌对目标候选（替代 hurt() 中直接 setTarget） ===

    @Nullable
    public LivingEntity getHostileTargetCandidate() { return hostileTargetCandidate; }

    public void setHostileTargetCandidate(@Nullable LivingEntity target) {
        this.hostileTargetCandidate = target;
    }

    public void rememberOwnerDefenseTarget(LivingEntity target, long durationTicks) {
        this.ownerDefenseTargetCandidate = target;
        this.ownerDefenseTargetUntil = level().getGameTime() + durationTicks;
    }

    @Nullable
    public LivingEntity getOwnerDefenseTargetCandidate() {
        if (ownerDefenseTargetCandidate == null || !ownerDefenseTargetCandidate.isAlive()
                || ownerDefenseTargetCandidate.level() != level()
                || level().getGameTime() > ownerDefenseTargetUntil) {
            ownerDefenseTargetCandidate = null;
        }
        return ownerDefenseTargetCandidate;
    }

    public void rememberOwnerAssistTarget(LivingEntity target, long durationTicks) {
        this.ownerAssistTargetCandidate = target;
        this.ownerAssistTargetUntil = level().getGameTime() + durationTicks;
    }

    @Nullable
    public LivingEntity getOwnerAssistTargetCandidate() {
        if (ownerAssistTargetCandidate == null || !ownerAssistTargetCandidate.isAlive()
                || ownerAssistTargetCandidate.level() != level()
                || level().getGameTime() > ownerAssistTargetUntil) {
            ownerAssistTargetCandidate = null;
        }
        return ownerAssistTargetCandidate;
    }

    /** 清除集团战术层写入的临时意图；不会清除 NPC 自己的受击记忆。 */
    public void clearOwnerCombatTargets() {
        this.ownerDefenseTargetCandidate = null;
        this.ownerAssistTargetCandidate = null;
        this.ownerDefenseTargetUntil = 0L;
        this.ownerAssistTargetUntil = 0L;
    }

    /** 判断坐骑当前是否被拴绳拴住 */
    public boolean isMountLeashed() {
        if (!hasMount()) return false;
        Entity mount = getMount();
        return mount instanceof Leashable l && l.isLeashed();
    }

    /**
     * 判断当前是否应下马。
     * true = 下马，false = 留在马上。
     */
    public boolean shouldDismount() {
        // 狭窄空间检测：头顶有方块
        if (!level().getBlockState(this.blockPosition().above(2)).isAir()) {
            return true;
        }
        if (!level().getBlockState(this.blockPosition().above()).isAir()
                && level().getBlockState(this.blockPosition().above()).isSolid()) {
            return true;
        }

        // 非战斗职业在工作场景下马
        boolean isWorkingProf = profession == NpcProfession.WORKER
                || profession == NpcProfession.FARMER
                || profession == NpcProfession.BLACKSMITH;
        if (isWorkingProf && this.getTarget() == null) {
            if (this.command == NpcCommand.IDLE || this.command == NpcCommand.STAY || this.command == NpcCommand.WORK) {
                return true;
            }
        }

        return false;
    }

    // === 栓绳与拴马 ===

    /** 检查背包是否有栓绳 */
    private boolean hasLeadInInventory() {
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            if (equipmentInventory.getItem(i).is(Items.LEAD)) return true;
        }
        return false;
    }

    /** 消耗一根栓绳 */
    private void consumeLeadFromInventory() {
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            ItemStack stack = equipmentInventory.getItem(i);
            if (stack.is(Items.LEAD)) {
                stack.shrink(1);
                break;
            }
        }
    }

    /** 回收一根栓绳到 NPC 背包 */
    public void addLeadToInventory() {
        ItemStack lead = new ItemStack(Items.LEAD);
        // 先尝试合并到已有的栓绳堆叠
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            ItemStack stack = equipmentInventory.getItem(i);
            if (stack.is(Items.LEAD) && stack.getCount() < stack.getMaxStackSize()) {
                stack.grow(1);
                return;
            }
        }
        // 没有可合并的堆叠，放到空槽位
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            if (equipmentInventory.getItem(i).isEmpty()) {
                equipmentInventory.setItem(i, lead);
                return;
            }
        }
        // 背包满了，掉落在地上（兜底）
        this.spawnAtLocation(lead);
    }

    /** 找最近的栅栏 */
    @Nullable
    private BlockPos findNearestFence(int range) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    pos.set(this.getX() + dx, this.getY() + dy, this.getZ() + dz);
                    if (this.level().getBlockState(pos).is(BlockTags.FENCES)) {
                        return pos.immutable();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 拴马到最近的栅栏。
     * @return true 拴马成功，false 失败（无栅栏/无栓绳/马已拴）
     */
    public boolean tetherHorse() {
        if (!this.hasMount() || this.level().isClientSide) return false;
        Entity mount = getMount();
        if (mount == null || !mount.isAlive()) return false;

        // 如果马已被拴，不重复操作
        if (mount instanceof Leashable l && l.isLeashed()) return false;

        BlockPos fencePos = findNearestFence(8);
        if (fencePos == null) return false;
        if (!hasLeadInInventory()) return false;

        consumeLeadFromInventory();
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(level(), fencePos);
        if (mount instanceof Leashable leashable) {
            leashable.setLeashedTo(knot, true);
        }
        return true;
    }

    /** 解拴马：只断开栓绳连接，不处理物品回收 */
    public void untetherHorse() {
        if (!this.hasMount() || this.level().isClientSide) return;
        Entity mount = getMount();
        if (mount == null || !mount.isAlive()) return;

        if (mount instanceof Leashable leashable && leashable.isLeashed()) {
            leashable.dropLeash(true, false);
        }
    }

    // === 喂马 ===

    /** 在背包中查找马匹食物，返回槽位索引，-1 表示没有 */
    private int findHorseFood() {
        for (int i = 0; i < equipmentInventory.getContainerSize(); i++) {
            if (isHorseFood(equipmentInventory.getItem(i))) return i;
        }
        return -1;
    }

    /** 判断是否为马匹食物 */
    private static boolean isHorseFood(ItemStack stack) {
        return stack.is(Items.WHEAT)
                || stack.is(Items.SUGAR)
                || stack.is(Items.APPLE)
                || stack.is(Items.GOLDEN_APPLE)
                || stack.is(Items.GOLDEN_CARROT)
                || stack.is(Items.HAY_BLOCK);
    }

    /** 根据食物类型返回治疗量 */
    private static float getHorseFoodHealAmount(ItemStack food) {
        if (food.is(Items.GOLDEN_APPLE)) return 10.0f;
        if (food.is(Items.GOLDEN_CARROT)) return 4.0f;
        if (food.is(Items.HAY_BLOCK)) return 20.0f;
        if (food.is(Items.APPLE)) return 3.0f;
        if (food.is(Items.SUGAR)) return 1.0f;
        if (food.is(Items.WHEAT)) return 2.0f;
        return 1.0f;
    }

    /**
     * 检查实体是否为盟友。
     * player 阵营 NPC 之间：同 Character ID → 盟友，不需要查声望。
     */
    public boolean isCombatAlly(LivingEntity entity) {
        if(com.jgeted.sagadyssey.social.combat.NaturalConflictData.ceasefire(this,entity))return true;
        if(com.jgeted.sagadyssey.social.combat.NaturalConflictData.opponents(this,entity))return false;
        if (entity instanceof Player player) {
            return isOwnedBy(player.getUUID());
        }
        if (entity instanceof NpcBase other) {
            // 同一社会人物 → 盟友；账号在永久死亡后不会维持这层关系。
            if (this.ownerCharacterId != null && this.ownerCharacterId.equals(other.ownerCharacterId)) {
                return true;
            }
            // 未迁移的旧存档临时按账号兼容；玩家上线后会立即补写 Character ID。
            if (this.ownerCharacterId == null && other.ownerCharacterId == null
                    && this.ownerUUID != null && this.ownerUUID.equals(other.ownerUUID)) {
                return true;
            }
            // 双方都是 player 阵营且同角色 → 盟友（防御性冗余）
            if (this.ownerCharacterId != null
                    && "sagadyssey:player".equals(this.faction != null ? this.faction.id() : null)
                    && "sagadyssey:player".equals(other.faction != null ? other.faction.id() : null)
                    && this.ownerCharacterId.equals(other.ownerCharacterId)) {
                return true;
            }
        }
        return false;
    }

    public NpcPersonalInventory getPersonalInventory() { return equipmentInventory; }

    /** @deprecated use {@link #getPersonalInventory()}; retained for source compatibility. */
    @Deprecated
    public NpcPersonalInventory getEquipmentInventory() { return equipmentInventory; }

    public SimpleContainer getTransportInventory() { return transportInventory; }

    public void setLogisticsMoveTarget(@Nullable BlockPos target) {
        this.logisticsMoveTarget = target == null ? null : target.immutable();
    }

    public void clearLogisticsMoveTarget() { this.logisticsMoveTarget = null; }

    public void reportWorkActivity(String activity, @Nullable BlockPos target, String detail) {
        if (level().isClientSide) return;
        this.workActivity = activity == null || activity.isBlank() ? "未知任务" : activity;
        this.workActivityTarget = target == null ? null : target.immutable();
        this.workActivityDetail = detail == null ? "" : detail;
        this.workActivityUpdatedAt = level().getGameTime();
    }

    public String getWorkActivity() { return workActivity; }
    public String getWorkActivityDetail() { return workActivityDetail; }
    @Nullable public BlockPos getWorkActivityTarget() { return workActivityTarget; }
    public long getWorkActivityAge() {
        return workActivityUpdatedAt < 0L ? -1L : Math.max(0L, level().getGameTime() - workActivityUpdatedAt);
    }

    public int nextConversationTurn() {
        int current = conversationTurn;
        conversationTurn = conversationTurn == Integer.MAX_VALUE ? 0 : conversationTurn + 1;
        return current;
    }

    public long getLastDamageGameTime() { return lastDamageGameTime; }

    public boolean wasRecentlyDamaged(long safetyTicks) {
        if (lastDamageGameTime == Long.MIN_VALUE) return false;
        long elapsed = level().getGameTime() - lastDamageGameTime;
        return elapsed >= 0L && elapsed < Math.max(0L, safetyTicks);
    }

    public void setPatrolMoveTarget(@Nullable BlockPos target) {
        this.patrolMoveTarget = target == null ? null : target.immutable();
    }

    public void clearPatrolMoveTarget() { this.patrolMoveTarget = null; }

    /** 专职运输者有 27 格货舱；商人兼职运输时只使用前 9 格。 */
    public int getTransportSlotLimit() {
        return getProfession() == NpcProfession.COURIER ? 27
                : getProfession() == NpcProfession.TRADER ? 9 : 0;
    }

    public UUID getCharacterId() {
        if (characterId == null) characterId = getUUID();
        return characterId;
    }

    /** Used by body replacement/migration code; normal gameplay never changes character identity. */
    public void setCharacterId(UUID characterId) {
        this.characterId = Objects.requireNonNull(characterId, "characterId");
    }

    public List<ItemStack> getInventoryMigrationOverflowSnapshot() {
        return inventoryMigrationOverflow.stream()
                .filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy)
                .toList();
    }

    private void storeMigrationOverflow(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        ItemStack remaining = stack.copy();
        for (ItemStack existing : inventoryMigrationOverflow) {
            if (!ItemStack.isSameItemSameComponents(existing, remaining)) continue;
            int moved = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            existing.grow(moved);
            remaining.shrink(moved);
            if (remaining.isEmpty()) return;
        }
        inventoryMigrationOverflow.add(remaining.copy());
    }

    /** 首次分配职业时给予初始装备 */
    public void applyInitialEquipment(NpcProfession profession) {
        switch (profession) {
            case NONE -> {}
            case WARRIOR -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
                setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
                equipmentInventory.setItem(0, new ItemStack(Items.BREAD, 3));
            }
            case ARCHER -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                addToBag(new ItemStack(Items.ARROW, 8));
                equipmentInventory.setItem(0, new ItemStack(Items.BREAD, 2));
            }
            case HEAVY -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
                setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                equipmentInventory.setItem(0, new ItemStack(Items.BREAD, 3));
            }
            case WORKER -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
                setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STONE_AXE));
                equipmentInventory.setItem(0, new ItemStack(Items.COBBLESTONE, 8));
            }
            case BLACKSMITH -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
                setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STONE_SWORD));
                equipmentInventory.setItem(0, new ItemStack(Items.COAL, 4));
                equipmentInventory.setItem(1, new ItemStack(Items.IRON_INGOT, 2));
            }
            case FARMER -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_HOE));
                equipmentInventory.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 4));
                equipmentInventory.setItem(1, new ItemStack(Items.CARROT, 4));
                equipmentInventory.setItem(2, new ItemStack(Items.POTATO, 4));
                equipmentInventory.setItem(3, new ItemStack(Items.BEETROOT_SEEDS, 4));
                equipmentInventory.setItem(4, new ItemStack(Items.BREAD, 2));
                equipmentInventory.setItem(5, new ItemStack(Items.MELON_SEEDS, 4));
                equipmentInventory.setItem(6, new ItemStack(Items.PUMPKIN_SEEDS, 4));
            }
            case BARD -> {
                equipmentInventory.setItem(0, new ItemStack(Items.BREAD, 4));
            }
            case MEDIC -> {
                setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
                equipmentInventory.setItem(0, new ItemStack(Items.GOLDEN_APPLE, 1));
                equipmentInventory.setItem(1, new ItemStack(Items.BREAD, 4));
                equipmentInventory.setItem(2, new ItemStack(Items.WHITE_WOOL, 6));
            }
            case TRADER -> {
                equipmentInventory.setItem(0, new ItemStack(Items.PAPER, 3));
                equipmentInventory.setItem(1, new ItemStack(Items.BOOK, 1));
            }
            case COURIER -> {
                equipmentInventory.setItem(0, new ItemStack(Items.BREAD, 4));
                equipmentInventory.setItem(1, new ItemStack(Items.LEAD, 1));
            }
        }
    }

    // === 便捷属性读取（从原版 Attribute 系统） ===

    /** 当前生命值 */
    public float getCurrentHp() { return this.getHealth(); }

    /** 最大生命值 */
    public float getMaxHp() { return this.getMaxHealth(); }

    /** 攻击力 */
    public float getAttackDamage() {
        return (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
    }

    /** 移动速度 */
    public float getSpeed() {
        return (float) this.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    /** 护甲值 */
    @Override
    public int getArmorValue() {
        return (int) this.getAttributeValue(Attributes.ARMOR);
    }

    /**
     * 原版 Mob.leashTooFarBehaviour 在绳断后会永久禁用 MOVE 标志，
     * 所有移动类 goal（含 FarmerWorkGoal）从此不再被轮询，NPC 原地冻结。
     * NPC 移动由命令系统驱动，不需要这个行为：绳断后立刻恢复 MOVE。
     */
    @Override
    public void leashTooFarBehaviour() {
        super.leashTooFarBehaviour();
        this.goalSelector.enableControlFlag(Goal.Flag.MOVE);
    }

    // === AI Goals ===

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new com.jgeted.sagadyssey.npc.ai.LowHpRetreatGoal(this));
        this.goalSelector.addGoal(0, new com.jgeted.sagadyssey.npc.ai.NpcHomeSleepGoal(this));
        // 新行为桥只在单个 NPC 被管理员显式切为 PLANNED 后运行。
        this.goalSelector.addGoal(1,
                new com.jgeted.sagadyssey.npc.brain.execution.NpcPlanExecutionGoal(this));
        this.goalSelector.addGoal(1, new com.jgeted.sagadyssey.npc.ai.NpcEatGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.StayGoal(this));
        this.goalSelector.addGoal(1, meleeAttackGoal = new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(1, new com.jgeted.sagadyssey.npc.ai.MountGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.NpcWorkCommuteGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.NpcWarehouseDepositGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.NpcGroundItemRecoveryGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.NpcHouseholdCareGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.MedicWorkGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.BlacksmithSmeltGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.BlacksmithWorkGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.TraderRouteGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.ConvoyEscortGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.PatrolGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.FollowOwnerGoal(this, 1.0D, 3.0F, 48.0F));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.NpcLogisticsGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.FarmerWorkGoal(this));
        this.goalSelector.addGoal(2, new com.jgeted.sagadyssey.npc.ai.WorkerWorkGoal(this));
        this.goalSelector.addGoal(3, new com.jgeted.sagadyssey.npc.ai.BardSupportGoal(this));
        // 待机 goal 只在 IDLE 命令下允许启动，WORK/FOLLOW/STAY 时完全不参与，避免抢占 MOVE/LOOK 挡住工作 goal
        this.goalSelector.addGoal(3, new RandomStrollGoal(this, 1.0D) {
            @Override
            public boolean canUse() {
                return NpcBase.this.getCommand() == NpcCommand.IDLE && super.canUse();
            }
        });
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F) {
            @Override
            public boolean canUse() {
                return NpcBase.this.getCommand() == NpcCommand.IDLE && super.canUse();
            }
        });
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                return NpcBase.this.getCommand() == NpcCommand.IDLE && super.canUse();
            }
        });
        this.goalSelector.addGoal(6, new com.jgeted.sagadyssey.npc.ai.NpcOpenDoorGoal(this));
        this.targetSelector.addGoal(0, combatGoal = new com.jgeted.sagadyssey.npc.ai.NpcCombatGoal(this));
    }

    /** 属性定义（注册实体时调用） */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.ARMOR, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean interruptedRest = this.isSleeping()
                || "回家休息".equals(this.workActivity)
                || "等待休息出发".equals(this.workActivity)
                || "脱战安全观察".equals(this.workActivity);
        if (this.profession == NpcProfession.HEAVY && this.command == NpcCommand.STAY) {
            amount *= 0.75F;
        }
        if (source.getEntity() instanceof Player player) {
            // 新阵营系统：非主人玩家攻击 NPC → NPC 变敌对（通过 canBeHostile）
            if (faction != null && !faction.canBeHostile() && !isOwnedBy(player.getUUID())) {
                // 对该玩家来说此 NPC 变为敌对行为（行为层，不改变阵营本身）
                // 旧系统兼容：仅对 old-faction-based 逻辑保留
            }
        }
        // 被非盟友攻击时记录候选目标（由 TargetSelector 优先级链消费）
        LivingEntity hostileAttacker = source.getEntity() instanceof LivingEntity attacker
                && attacker.isAlive() && !isCombatAlly(attacker) ? attacker : null;
        boolean hostileHit = hostileAttacker != null;
        if (hostileAttacker != null) this.hostileTargetCandidate = hostileAttacker;
        float healthBeforeDamage = this.getHealth();
        boolean damaged = super.hurt(source, amount);
        if (damaged && !this.level().isClientSide) {
            this.lastDamageGameTime = this.level().getGameTime();
            if (this.isSleeping()) this.stopSleeping();
            if (interruptedRest) {
                this.getNavigation().stop();
                reportWorkActivity(hostileHit ? "受到攻击，暂缓休息" : "受到伤害，暂缓休息",
                        this.blockPosition(), "等待连续安全 10 秒后再回家");
            }
            if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                long now = serverLevel.getGameTime();
                float observedDamage = Math.max(0.1F, healthBeforeDamage - this.getHealth());
                UUID attackerCharacter = hostileAttacker instanceof NpcBase other
                        ? other.getCharacterId()
                        : hostileAttacker instanceof net.minecraft.server.level.ServerPlayer player
                        ? CharacterRegistry.currentId(player) : null;
                UUID woundId = UUID.randomUUID();
                double armorCoverage = Math.min(0.85D, this.getArmorValue() / 20.0D);
                com.jgeted.sagadyssey.social.combat.InjuryService.recordHit(
                        com.jgeted.sagadyssey.social.combat.InjuryData.get(serverLevel.getServer()),
                        woundId, getCharacterId(), attackerCharacter,
                        com.jgeted.sagadyssey.social.combat.InjuryService.classify(source.getMsgId()),
                        observedDamage, armorCoverage, now, woundId);
                com.jgeted.sagadyssey.social.combat.NpcCombatRuntime.wounded(this,attackerCharacter,woundId);
                var moraleData = com.jgeted.sagadyssey.social.combat.CombatMoraleData
                        .get(serverLevel.getServer());
                moraleData.ensure(getCharacterId(), 50, getMoral(), now);
                int moraleLoss = Math.max(1, Math.min(12,
                        (int) Math.ceil(observedDamage / 3.0F)));
                var eventType = observedDamage >= 5.0F
                        ? com.jgeted.sagadyssey.social.combat.MoraleEventType.SERIOUS_WOUND
                        : com.jgeted.sagadyssey.social.combat.MoraleEventType.TOOK_DAMAGE;
                var profile = moraleData.record(new com.jgeted.sagadyssey.social.combat.MoraleEvent(
                        UUID.randomUUID(), getCharacterId(), null, eventType, -moraleLoss,
                        woundId, now, "受到 " + source.getMsgId() + " 伤害"));
                setMoral(profile.current());
            }
        }
        if (damaged && hostileHit && !this.level().isClientSide) {
            if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                UUID subject = hostileAttacker instanceof NpcBase other ? other.getCharacterId()
                        : hostileAttacker instanceof net.minecraft.server.level.ServerPlayer player
                        ? CharacterRegistry.currentId(player) : null;
                com.jgeted.sagadyssey.social.life.NpcLifeData.get(serverLevel.getServer()).remember(
                        getCharacterId(), new com.jgeted.sagadyssey.social.life.NpcMemoryRecord(
                                "attack", subject,
                                "被 " + hostileAttacker.getName().getString() + " 伤害 "
                                        + Math.round(amount) + " 点",
                                -Math.min(100, 25 + Math.round(amount * 5.0F)),
                                Math.min(100, 35 + Math.round(amount * 4.0F)),
                                serverLevel.getGameTime(), serverLevel.getGameTime() + 72000L),
                        serverLevel.getGameTime());
            }
        }
        return damaged;
    }

    // === 右键交互 ===

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);

        // === 指挥杖：选择 / 取消选择集团成员 ===
        if (stack.is(ModItems.NPC_COMMAND_WAND.get())) {
            if (player.level().isClientSide) {
                // 客户端不打开命令 GUI（不发 request_stats），交给服务端处理
                return InteractionResult.SUCCESS;
            }
            if (!isOwnedBy(player.getUUID())) {
                player.displayClientMessage(Component.literal("§c你与这个 NPC 没有生效中的指挥关系"), true);
                return InteractionResult.SUCCESS;
            }
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                var data = CommandGroupAttachments.get(serverPlayer);
                data.registerMember(getCharacterId(), getNpcName());
                if (!data.hasMember(getCharacterId())) {
                    player.displayClientMessage(Component.literal("§c集团成员已达上限（1024）"), true);
                    return InteractionResult.SUCCESS;
                }
                data.toggleSelected(getCharacterId());
                boolean selected = data.isSelected(getCharacterId());
                player.displayClientMessage(Component.literal((selected ? "§a已选中 " : "§e已取消选择 ")
                        + getNpcName()), true);
                serverPlayer.serverLevel().sendParticles(
                        selected ? net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER
                                : net.minecraft.core.particles.ParticleTypes.SMOKE,
                        getX(), getY() + getBbHeight() + 0.25, getZ(),
                        8, 0.3, 0.2, 0.3, 0.02);
                CommandGroupAttachments.sync(serverPlayer, false);
            }
            return InteractionResult.SUCCESS;
        }

        // === 栓绳交互 ===
        if (stack.is(Items.LEAD)) {
            // 检测玩家是否在牵马
            Leashable leashedHorse = null;
            for (var horse : this.level().getEntitiesOfClass(AbstractHorse.class,
                    player.getBoundingBox().inflate(12))) {
                if (horse instanceof Leashable leashable
                        && leashable.isLeashed()
                        && leashable.getLeashHolder() == player) {
                    leashedHorse = leashable;
                    break;
                }
            }

            if (leashedHorse != null) {
                AbstractHorse horse = (AbstractHorse) leashedHorse;
                if (horse.isTamed() && horse.getOwnerUUID() != null
                        && horse.getOwnerUUID().equals(player.getUUID())) {
                    if (!player.level().isClientSide) {
                        this.bindMount(horse);
                        player.displayClientMessage(
                                Component.literal("§a" + this.getNpcName() + " 已绑定坐骑！"), true);
                    }
                } else {
                    if (!player.level().isClientSide) {
                        player.displayClientMessage(
                                Component.literal("§c这匹马不属于你"), true);
                    }
                }
                return InteractionResult.SUCCESS;
            }
        }
        // === 栓绳交互结束 ===

        // 潜行右键只是明确打开递交意图选择，不会在世界中直接转移物品。
        if (NpcInteractionPolicy.opensHandoverShortcut(!stack.isEmpty(), player.isShiftKeyDown())) {
            if (!player.level().isClientSide
                    && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                PacketDistributor.sendToPlayer(serverPlayer, new HandoverPromptPayload(getId(), hand,
                        getNpcName(), stack.getHoverName().getString(), stack.getCount()));
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }

        if (player.level().isClientSide) {
            // 普通右键无论是否手持物品，都先打开基于观察者知识生成的人物投影。
            NpcInteractionPacket.requestStats(this.getId());
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    // === NBT 持久化 ===

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("SagadysseyNpcDataVersion", NpcInventoryMigration.DATA_VERSION);
        tag.putUUID(NpcCharacterIdentityMigration.CHARACTER_ID_KEY, getCharacterId());
        tag.putString("NpcName", this.customName);
        tag.putString("Profession", this.profession.name());
        tag.putString("SocialClass", this.socialClass.name());
        tag.putString("Office", this.office.name());
        tag.putString("Gender", this.gender.name());
        tag.putString("FaceVariant", this.faceVariant.name());
        tag.putInt("Experience", this.experience);
        tag.putInt("MerchantSeed", this.merchantTradeSeed);
        tag.putLong("MerchantRestockAt", this.merchantRestockAt);
        tag.putString("MerchantRegion", this.merchantRegion.name());
        tag.putInt("BardFollowTicks", this.bardFollowTicks);
        tag.putInt("ActionStartCount", this.actionStartCount);
        tag.putInt("Kills", this.kills);
        tag.putInt("Moral", this.moral);
        tag.putInt("RecruitmentCost", this.recruitmentCost);
        tag.putString("NpcCommand", this.command.name());
        if (this.guardAnchor != null) {
            tag.putIntArray("GuardAnchor", new int[]{guardAnchor.getX(), guardAnchor.getY(), guardAnchor.getZ()});
        }
        if (this.patrolCenter != null) {
            tag.putIntArray("PatrolCenter", new int[]{patrolCenter.getX(), patrolCenter.getY(), patrolCenter.getZ()});
            tag.putInt("PatrolRadius", patrolRadius);
        }
        tag.putString("FarmMode", this.farmMode.name());
        if (this.workZoneMin != null && this.workZoneMax != null) {
            tag.putIntArray("WorkZoneMin", new int[]{workZoneMin.getX(), workZoneMin.getY(), workZoneMin.getZ()});
            tag.putIntArray("WorkZoneMax", new int[]{workZoneMax.getX(), workZoneMax.getY(), workZoneMax.getZ()});
        }
        tag.putString("Faction", this.faction != null ? this.faction.id() : "sagadyssey:wilderness");
        if (this.originalFaction != null) {
            tag.putString("OriginalFaction", this.originalFaction);
        }
        tag.putString("NpcTier", this.npcTier.name());
        if (this.ownerUUID != null) {
            tag.putUUID("OwnerUUID", this.ownerUUID);
        }
        if (this.ownerCharacterId != null) {
            tag.putUUID("OwnerCharacterId", this.ownerCharacterId);
        }
        if (this.homeSettlementId != null) {
            tag.putUUID("HomeSettlementId", this.homeSettlementId);
        }

        if (this.mountUUID != null) {
            tag.putUUID("MountUUID", this.mountUUID);
        }
        tag.putBoolean("MountSaddled", mountSaddled);
        tag.putBoolean("LeadMountMode", leadMountMode);

        ContainerHelper.saveAllItems(tag, equipmentInventory.getItems(), level().registryAccess());
        tag.putLong("PersonalInventoryRevision", equipmentInventory.revision());
        if (inventoryMigrationOverflow.stream().anyMatch(stack -> !stack.isEmpty())) {
            ListTag overflowTag = new ListTag();
            inventoryMigrationOverflow.stream()
                    .filter(stack -> !stack.isEmpty())
                    .forEach(stack -> overflowTag.add(stack.save(level().registryAccess())));
            tag.put("InventoryMigrationOverflow", overflowTag);
        }
        CompoundTag cargoTag = new CompoundTag();
        ContainerHelper.saveAllItems(cargoTag, transportInventory.getItems(), level().registryAccess());
        tag.put("TransportCargo", cargoTag);

        // 交易数据持久化
        tag.putInt("UnlockedTradeLevel", this.unlockedTradeLevel);
        tag.putDouble("MerchantMarkup", this.merchantMarkupRate);
        CompoundTag tradesTag = new CompoundTag();
        tradesTag.putInt("Count", activeTrades.size());
        for (int i = 0; i < activeTrades.size(); i++) {
            NpcTradeOffer t = activeTrades.get(i);
            CompoundTag entry = new CompoundTag();
            entry.put("CostItem", t.costItem().save(level().registryAccess()));
            entry.putInt("CostMin", t.costMin());
            entry.putInt("CostMax", t.costMax());
            entry.put("ResultItem", t.resultItem().save(level().registryAccess()));
            entry.putInt("ResultMin", t.resultMin());
            entry.putInt("ResultMax", t.resultMax());
            entry.putInt("MinLevel", t.minNpcLevel());
            tradesTag.put("t" + i, entry);
        }
        tag.put("ActiveTrades", tradesTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        // Vanilla omits wholly empty equipment lists. Constructor spawn gear must not
        // survive that omission and silently appear again when an empty-handed NPC loads.
        for (EquipmentSlot slot : EquipmentSlot.values()) setItemSlot(slot, ItemStack.EMPTY);
        equipmentInventory.clearContent();
        super.readAdditionalSaveData(tag);
        this.characterId = NpcCharacterIdentityMigration.resolve(tag, getUUID());
        if (tag.contains("NpcName")) {
            this.customName = tag.getString("NpcName");
        }
        if (tag.contains("Profession")) {
            try {
                this.profession = NpcProfession.valueOf(tag.getString("Profession"));
            } catch (IllegalArgumentException e) {
                this.profession = NpcProfession.NONE;
            }
        }
        if (tag.contains("SocialClass") || tag.contains("Office")) {
            this.socialClass = NpcSocialClass.byName(tag.getString("SocialClass"));
            this.office = NpcOffice.byName(tag.getString("Office"));
        } else if (tag.contains("SocialStatus")) {
            // v1 身份存档迁移：线性状态拆为阶层与职务。
            NpcSocialStatus legacy = NpcSocialStatus.byName(tag.getString("SocialStatus"));
            this.socialClass = legacy.defaultSocialClass();
            this.office = legacy.defaultOffice();
        } else {
            this.socialClass = NpcSocialClass.COMMONER;
            this.office = NpcOffice.NONE;
        }
        if (tag.contains("Gender")) {
            this.gender = NpcGender.byName(tag.getString("Gender"));
        }
        if (tag.contains("FaceVariant")) {
            this.faceVariant = NpcFaceVariant.byName(tag.getString("FaceVariant"));
        }
        if (tag.contains("NpcLevel") && tag.getInt("NpcLevel") > 1 && !tag.contains("Experience")) {
            // 向后兼容：旧存档有 NpcLevel 但没有 Experience，按最低经验转换
            int oldLevel = tag.getInt("NpcLevel");
            this.experience = switch (oldLevel) {
                case 2 -> 200;
                case 3 -> 600;
                case 4 -> 1400;
                default -> 0;
            };
        }
        if (tag.contains("Experience")) {
            this.experience = tag.getInt("Experience");
        }
        if (tag.contains("MerchantSeed")) {
            this.merchantTradeSeed = tag.getInt("MerchantSeed");
        }
        this.merchantRestockAt = tag.contains("MerchantRestockAt")
                ? tag.getLong("MerchantRestockAt") : -1L;
        this.merchantRegion = tag.contains("MerchantRegion")
                ? NpcMerchantRegion.byName(tag.getString("MerchantRegion")) : NpcMerchantRegion.PLAINS;
        if (tag.contains("BardFollowTicks")) {
            this.bardFollowTicks = tag.getInt("BardFollowTicks");
        }
        this.actionStartCount = Math.max(0, tag.getInt("ActionStartCount"));
        if (tag.contains("Kills")) {
            this.kills = tag.getInt("Kills");
        }
        if (tag.contains("Moral")) {
            setMoral(tag.getInt("Moral"));
        }
        if (tag.contains("RecruitmentCost")) {
            this.recruitmentCost = tag.getInt("RecruitmentCost");
        }
        if (tag.contains("OwnerUUID")) {
            this.ownerUUID = tag.getUUID("OwnerUUID");
        } else {
            this.ownerUUID = null;
        }
        this.entityData.set(DATA_OWNER_UUID, Optional.ofNullable(this.ownerUUID));
        this.ownerCharacterId = tag.hasUUID("OwnerCharacterId")
                ? tag.getUUID("OwnerCharacterId") : null;
        this.homeSettlementId = tag.hasUUID("HomeSettlementId")
                ? tag.getUUID("HomeSettlementId") : null;
        // 原阵营 ID（招募前保存的完整 ID，如 "sagadyssey:kingdom"）
        this.originalFaction = tag.contains("OriginalFaction")
                ? tag.getString("OriginalFaction") : null;
        if (tag.contains("MountUUID")) {
            this.mountUUID = tag.getUUID("MountUUID");
        } else {
            this.mountUUID = null;
        }
        this.mountSaddled = tag.getBoolean("MountSaddled");
        this.leadMountMode = tag.getBoolean("LeadMountMode");
        if (tag.contains("NpcCommand")) {
            try {
                this.command = NpcCommand.valueOf(tag.getString("NpcCommand"));
            } catch (IllegalArgumentException e) {
                this.command = NpcCommand.IDLE;
            }
        }
        if (tag.contains("FarmMode")) {
            try {
                this.farmMode = FarmMode.valueOf(tag.getString("FarmMode"));
            } catch (IllegalArgumentException e) {
                this.farmMode = FarmMode.AUTO;
            }
        }
        if (tag.contains("WorkZoneMin") && tag.contains("WorkZoneMax")) {
            int[] min = tag.getIntArray("WorkZoneMin");
            int[] max = tag.getIntArray("WorkZoneMax");
            if (min.length == 3 && max.length == 3) {
                this.workZoneMin = new BlockPos(min[0], min[1], min[2]);
                this.workZoneMax = new BlockPos(max[0], max[1], max[2]);
            }
        }
        if (tag.contains("Faction")) {
            try {
                String factionId = tag.getString("Faction");
                // 兼容旧格式（"HOSTILE"/"NEUTRAL"/"FRIENDLY"）和新格式（"sagadyssey:kingdom"）
                if (factionId.contains(":")) {
                    this.faction = FactionRegistry.get(factionId);
                } else {
                    // 旧 NpcFaction 枚举值，映射到新 Faction
                    this.faction = switch (factionId) {
                        case "HOSTILE" -> FactionRegistry.get("sagadyssey:bandit");
                        case "FRIENDLY" -> FactionRegistry.get("sagadyssey:kingdom");
                        default -> FactionRegistry.get("sagadyssey:wilderness");
                    };
                }
            } catch (Exception e) {
                this.faction = FactionRegistry.get("sagadyssey:wilderness");
            }
        }
        if (tag.contains("NpcTier")) {
            try {
                this.npcTier = IFactionInteractable.NpcTier.valueOf(tag.getString("NpcTier"));
            } catch (IllegalArgumentException e) {
                this.npcTier = IFactionInteractable.NpcTier.NORMAL;
            }
        }

        if (tag.contains("Items")) {
            equipmentInventory.clearContent();
            ContainerHelper.loadAllItems(tag, equipmentInventory.getItems(), level().registryAccess());
        }
        inventoryMigrationOverflow.clear();
        if (tag.contains("InventoryMigrationOverflow")) {
            ListTag overflowTag = tag.getList("InventoryMigrationOverflow", Tag.TAG_COMPOUND);
            for (int index = 0; index < overflowTag.size(); index++) {
                ItemStack.parse(level().registryAccess(), overflowTag.getCompound(index))
                        .filter(stack -> !stack.isEmpty())
                        .ifPresent(inventoryMigrationOverflow::add);
            }
        }
        boolean migratedSpecialSlots = false;
        if (tag.contains("BowSlot") || tag.contains("ArrowSlot")) {
            List<ItemStack> legacySpecialSlots = new ArrayList<>();
            if (tag.contains("BowSlot")) {
                legacySpecialSlots.add(ItemStack.parse(level().registryAccess(),
                        tag.getCompound("BowSlot")).orElse(ItemStack.EMPTY));
            }
            if (tag.contains("ArrowSlot")) {
                legacySpecialSlots.add(ItemStack.parse(level().registryAccess(),
                        tag.getCompound("ArrowSlot")).orElse(ItemStack.EMPTY));
            }
            for (ItemStack overflow : NpcInventoryMigration.mergeLegacySlots(
                    equipmentInventory, legacySpecialSlots)) {
                storeMigrationOverflow(overflow);
            }
            migratedSpecialSlots = true;
        }
        long savedRevision = tag.contains("PersonalInventoryRevision")
                ? tag.getLong("PersonalInventoryRevision") : 0L;
        equipmentInventory.restoreRevision(savedRevision + (migratedSpecialSlots ? 1L : 0L));
        if (tag.contains("GuardAnchor")) {
            int[] anchor = tag.getIntArray("GuardAnchor");
            if (anchor.length == 3) this.guardAnchor = new BlockPos(anchor[0], anchor[1], anchor[2]);
        } else if (this.command == NpcCommand.STAY) {
            this.guardAnchor = this.blockPosition().immutable();
        }
        if (tag.contains("PatrolCenter")) {
            int[] center = tag.getIntArray("PatrolCenter");
            if (center.length == 3) this.patrolCenter = new BlockPos(center[0], center[1], center[2]);
            this.patrolRadius = Math.max(4, Math.min(48, tag.getInt("PatrolRadius")));
        }
        transportInventory.clearContent();
        if (tag.contains("TransportCargo")) {
            ContainerHelper.loadAllItems(tag.getCompound("TransportCargo"),
                    transportInventory.getItems(), level().registryAccess());
        }

        // 交易数据恢复
        if (tag.contains("UnlockedTradeLevel")) {
            this.unlockedTradeLevel = tag.getInt("UnlockedTradeLevel");
        }
        if (tag.contains("MerchantMarkup")) {
            this.merchantMarkupRate = tag.getDouble("MerchantMarkup");
        }
        this.activeTrades.clear();
        if (tag.contains("ActiveTrades")) {
            CompoundTag tradesTag = tag.getCompound("ActiveTrades");
            int count = tradesTag.getInt("Count");
            for (int i = 0; i < count; i++) {
                CompoundTag entry = tradesTag.getCompound("t" + i);
                ItemStack costItem = ItemStack.parse(level().registryAccess(), entry.getCompound("CostItem")).orElse(ItemStack.EMPTY);
                ItemStack resultItem = ItemStack.parse(level().registryAccess(), entry.getCompound("ResultItem")).orElse(ItemStack.EMPTY);
                if (costItem.isEmpty() || resultItem.isEmpty()) continue;
                int costMin = entry.getInt("CostMin");
                int costMax = entry.getInt("CostMax");
                int resultMin = entry.getInt("ResultMin");
                int resultMax = entry.getInt("ResultMax");
                int minLevel = entry.getInt("MinLevel");
                this.activeTrades.add(new NpcTradeOffer(costItem, costMin, costMax, resultItem, resultMin, resultMax, minLevel));
            }
        }

        // 旧存档/数据包未加载兜底：faction 不应为 null（field 初始化时 registry 可能未就绪）
        if (this.faction == null) {
            this.faction = FactionRegistry.get("sagadyssey:wilderness");
            if (this.faction == null) {
                // registry 彻底未加载（极端时序），player 阵营有硬编码兜底，永不返回 null
                this.faction = FactionRegistry.getPlayerFaction();
            }
        }

        // 旧存档静默迁移：有主人但还属于原阵营 → 自动切到 player 阵营
        if (this.ownerUUID != null && !"sagadyssey:player".equals(this.faction.id())) {
            Sagadyssey.LOGGER.info("迁移已招募 NPC: {} 从 {} 转到 player 阵营 (owner={})",
                    this.getDisplayName().getString(),
                    this.faction != null ? this.faction.id() : "null",
                    this.ownerUUID);
            this.originalFaction = this.faction != null ? this.faction.id() : null;
            this.faction = FactionRegistry.getPlayerFaction();
        }

        // SynchedEntityData 不随实体 NBT 自动持久化，加载旧世界后必须显式恢复客户端外观。
        this.entityData.set(DATA_PROFESSION, this.profession.ordinal());
        this.entityData.set(DATA_SOCIAL_CLASS, this.socialClass.ordinal());
        this.entityData.set(DATA_OFFICE, this.office.ordinal());
        this.entityData.set(DATA_GENDER, this.gender.ordinal());
        this.entityData.set(DATA_FACE_VARIANT, this.faceVariant.ordinal());
        this.getAttribute(Attributes.KNOCKBACK_RESISTANCE)
                .setBaseValue(this.profession == NpcProfession.HEAVY ? 0.65D : 0.0D);
        syncVisibleName();
    }
}
