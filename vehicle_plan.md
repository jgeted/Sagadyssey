# Vehicle 模块实施计划

**目标**: NPC 可以骑乘马/驴/骡/骆驼等原版坐骑
**版本基线**: v1.3.0 → v1.4.0

---

## 设计概要

NPC 骑马的底层逻辑：
1. NPC 直接骑到马背上（成为马的 passenger）
2. NPC 的 AI 决定方向（跟着主人、巡逻、追击等）
3. 马的属性（速度、跳跃）保持不变，NPC 不改变马的移动能力
4. NPC 通过设置自身 `xxa`/`zza` 向马发出移动指令（马会自动读取骑手的输入）

---

## Phase 1：NPC 坐骑绑定系统

在 NpcBase 中添加坐骑绑定能力。

### 1.1 NpcBase 字段 & NBT

```java
@Nullable
private UUID mountUUID = null;           // 绑定的坐骑 UUID
@Nullable
private Entity mountEntity = null;        // 缓存的坐骑引用（每 tick 刷新生效）
private boolean isRidingMount = false;    // 是否正在骑乘
```

NBT 持久化在 `addAdditionalSaveData()` / `readAdditionalSaveData()` 中：

```
mountUUID → UUID
```

### 1.2 核心方法

```java
// 绑定坐骑（设置 mountUUID，不执行骑乘动作）
public void bindMount(Entity mount);

// 解绑坐骑
public void unbindMount();

// 获取绑定的坐骑实体（懒加载，缓存到 mountEntity）
@Nullable
public Entity getMount();

// 检查是否有绑定的坐骑
public boolean hasMount();

// 骑上坐骑
public void mountToBind();

// 从坐骑下来
public void dismountFromBind();
```

**挂载点**: 在 NpcBase 现有 getter/setter 区域添加。

**新增文件**: 无，直接修改 `NpcBase.java`

---

## Phase 2：NPC 骑马 AI

这是最核心的部分——NPC 如何控制马移动。

### 2.1 NPC 驱马原理

原版马接受骑手控制的机制：

```
马.travel() → 读取 getControllingPassenger().xxa/zza → 换算成移动
```

NPC 的驱马方式：在 `NpcBase.tick()` 中，如果 NPC 正在骑乘坐骑，根据 AI 目标计算方向并设置 `this.xxa` 和 `this.zza`。

**核心逻辑** (在 NpcBase.tick() 尾部追加)：

```java
if (!this.level().isClientSide && this.isPassengerOfMount()) {
    // 获取 AI 想要去的方向（导航目标、跟随目标、攻击目标等）
    Vec3 moveTarget = getAiMoveTarget();
    if (moveTarget != null) {
        Vec3 toTarget = moveTarget.subtract(this.position());
        double dist = toTarget.horizontalDistance();
        if (dist > 2.0) {
            // 面向目标
            float targetYaw = (float) (Math.atan2(toTarget.z, toTarget.x) * 180.0 / Math.PI) - 90.0F;
            this.setYRot(targetYaw);
            this.yHeadRot = targetYaw;
            // 向前移动
            this.zza = 1.0F;
            // 如果目标较远，可以跑
            this.xxa = dist > 10.0 ? 1.0F : 0.0F;
        } else {
            this.zza = 0.0F;
            this.xxa = 0.0F;
        }
    } else {
        this.zza = 0.0F;
        this.xxa = 0.0F;
    }
}
```

### 2.2 AbstractHorse Mixin

需要确保马能接受 NPC 作为正确骑手。

**文件**: `mixin/AbstractHorseRideMixin.java`

```java
@Mixin(AbstractHorse.class)
```

**注入点**: `getControllingPassenger()`

原版逻辑要求马已驯服且有鞍才会让骑手控制。NPC 骑乘时同样需要满足这个条件。

> **注意**: 原版 `getControllingPassenger()` 已经对所有 `LivingEntity` 类型的乘客放行（只要马已驯服且有鞍）。NpcBase 是 `PathfinderMob` → `LivingEntity`，所以默认就应该能用。

如果需要额外兼容（比如 NPC 骑乘不需要马完全驯服），加入：

```java
@Inject(method = "getControllingPassenger", at = @At("HEAD"), cancellable = true)
private void sagadyssey$allowNpcControl(CallbackInfoReturnable<LivingEntity> cir) {
    Entity passenger = this.getFirstPassenger();
    if (passenger instanceof NpcBase && this.isSaddled()) {
        cir.setReturnValue((NpcBase) passenger);
    }
}
```

### 2.3 路径导航适配

NPC 坐在马上时，不能用自己的腿走路导航。AI Goal 需要适配：

**核心改动**: NPC 在马上时，AI 仍然正常计算目标位置，但 NPC 的 navigation 不用于移动，而是通过 `tick()` 中的骑马驱马逻辑来实际移动。

**影响到的现有 Goal 类**:

| Goal | 改动 |
|------|------|
| `FollowOwnerGoal` | 骑乘时导航目标照算，移动改成通过马实现 |
| `StayGoal` | 待命时 NPC 可以选择骑在马上或下马（配置可选） |
| `NpcHostileGoal` | 追击目标时骑马冲锋，近战职业靠近后下马步战或马上打 |
| `LowHpRetreatGoal` | 低血时骑马逃走更快 |
| `ProtectOwnerGoal` | 骑马追击威胁，协同时移动更快 |

**简化策略**: 这些 Goal 本身逻辑不变——它们仍计算目标位置、设置 navigation。在 NpcBase 的 tick 中统一判断：如果 NPC 骑在马上，覆盖移动方式（从步行改为骑马驱马）。

---

## Phase 3：交互界面 — 分配坐骑

### 3.1 现有 NPC 交互界面扩展

**文件**: `NpcInteractionScreen.java`

当前流程：右键 NPC → 请求 stats → 显示交互界面

**新增**: 在交互界面中增加"坐骑"面板：

```
┌─────────────────────────┐
│  NPC: WARRIOR Lv.2      │
│  HP: ████████░░ 16/20   │
│  ───────────────────── │
│  [人物] [装备] [交易] [坐骑] ← 新Tab |
│  ───────────────────── │
│  坐骑:                    │
│  [ 马头像 + 名称 ]       │
│  血量: ██████░░ 15/20   │
│  速度: ★★★★☆           │
│                          │
│  [分配坐骑] [解除绑定]   │  ← 按钮
│  坐骑状态: 跟随中 ✔      │
└─────────────────────────┘
```

### 3.2 分配坐骑流程

点击"分配坐骑"按钮 → 发送网络包到服务端 → 服务端搜索 NPC 周围 10 格内所有已驯服且属于该玩家的马 → 返回可选列表 → 客户端显示选择弹窗 → 玩家选择 → 确认绑定。

**新网络包**:

| 包名 | 方向 | 说明 |
|------|------|------|
| `MountAssignRequestPacket` | C→S | 请求周围可选坐骑列表 |
| `MountAssignListPacket` | S→C | 返回候选坐骑列表 |
| `MountBindPacket` | C→S | 确认绑定某坐骑 |
| `MountUnbindPacket` | C→S | 解除绑定 |

### 3.3 右键快速绑定（方式 B）

除了 GUI 方式，额外支持：牵着已驯服的马右键 NPC → 立即绑定。

**实现**: 修改 NPC 的 `mobInteract()` 方法或者加 EventHandler 监听 `PlayerInteractEvent.EntityInteract`：

```java
// 伪逻辑
if (player.getMainHandItem().is(Items.LEAD) && 
    // ... 检测玩家正在牵着一匹马
    target instanceof AbstractHorse horse && 
    horse.isTamed() && 
    horse.isOwnedBy(player)) {
    // 绑定马到 NPC
    npc.bindMount(horse);
    return InteractionResult.SUCCESS;
}
```

**新增文件**: `vehicle/VehicleEventHandler.java`（集中处理 Vehicle 相关交互事件）

---

## Phase 4：骑乘 AI 行为适配

### 4.1 上下马规则（已确认）

| 场景 | 行为 |
|------|------|
| 跟随主人移动 | 骑马跟随，不下马 |
| 巡逻 | 骑马巡逻，不下马 |
| 战斗中追击/冲锋 | 骑马追击 |
| 近战接敌 | 在马上战斗 |
| 非战斗职业工作（WORKER/FARMER/BLACKSMITH） | 到达工作地点下马 |
| 进入室内/狭窄空间 | 下马 |
| STAY 待命 | 可选骑在马上或下马 |
| 与玩家交互（打开界面） | 下马 |
| BARD/MEDIC/TRADER | 全程骑乘，到达目的地时下马 |

**实现框架**: 在 NpcBase 中添加 `shouldDismount()` 方法，返回当前情况下是否应下马：

```java
public boolean shouldDismount() {
    // 玩家在交互
    if (/* 当前被玩家交互中 */) return true;
    
    // 室内/狭窄空间检测
    if (isInNarrowSpace()) return true;
    
    // 非战斗职业工作
    NpcProfession prof = getProfession();
    if (isWorkTime() && (prof == NpcProfession.WORKER || 
                         prof == NpcProfession.FARMER || 
                         prof == NpcProfession.BLACKSMITH)) {
        return true;
    }
    
    return false;
}
```

在 NpcBase.tick() 中定期检查：

```java
if (this.isPassengerOfMount() && shouldDismount()) {
    this.dismountFromBind();
}
if (!this.isPassengerOfMount() && hasMount() && !shouldDismount() && getMount() != null) {
    if (getMount() instanceof LivingEntity mount && mount.isAlive() && isSaddled(mount)) {
        this.startRiding(mount);
    }
}
```

### 4.2 FollowOwnerGoal 适配

当前逻辑：NPC 走到主人附近。当 NPC 骑马时：
- 目标位置计算不变
- 驱动马移动（通过 tick() 中的骑马驱马逻辑）
- 跟随距离可适当扩大（马跑得快，不会跟丢）
- 主人骑乘时：NPC 也能骑马跟随，速度匹配

### 4.3 StayGoal 适配

当前逻辑：NPC 原地待命。骑马时：
- 默认骑在马上待命（不下马，可随时追击或跟随）
- 也可选择下马站立（配置选项）

### 4.4 NpcHostileGoal 适配

当前逻辑：NPC 检测威胁并攻击。骑马时：
- 追击阶段：骑马冲锋，速度更快
- 接敌后：WARRIOR/HEAVY 在马上战斗；ARCHER 骑射
- 非战斗职业：骑马远离战斗或下马逃跑

---

## Phase 5：生成 & 生命周期

### 5.1 出生带马

在 `NpcFactionEvents` 或新的 `VehicleEventHandler` 中监听 NPC 生成事件：

```java
// NPC 生成后，概率 50% 附赠一匹马
if (npc.random.nextFloat() < 0.5f) {
    EntityType<?> mountType = selectMountFor(npc); // 马/驴/骡/骆驼
    Entity mount = mountType.spawn(level, npc.blockPosition(), ...);
    if (mount instanceof AbstractHorse horse) {
        horse.tameWithName(npc.getOwnerUUID() != null ? 
            level.getPlayerByUUID(npc.getOwnerUUID()) : null);
        horse.setTamed(true);
        horse.setSaddled(true);
   