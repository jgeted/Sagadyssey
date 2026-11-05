# 阶段 1：NPC 行为计划内核

## 1. 目标

阶段 1 只建立纯逻辑内核，不导航、不修改方块、不转移物品，也不改变现有 NPC 的游戏内行为。

它必须回答五个问题：

1. NPC 当前有哪些可行候选行为；
2. 哪些候选因时间、知识、前置条件或硬性禁令被淘汰；
3. 为什么选择某个具体候选；
4. 计划目前处于哪个阶段，能否被打断；
5. 计划取消、失败或结束时，是否释放了全部预留。

阶段完成后，现有 Goal 仍负责游戏内动作。阶段 2 才加入实体桥和按 NPC 灰度启用的新模式。

## 2. 模块边界

```text
context     决策时刻的角色主观快照
candidate   各行为模块提出的候选
plan        已选中的具体承诺和状态机
reservation 跨 NPC 互斥资源的纯逻辑预留
execution   未来实体执行器必须遵守的接口
scheduler   过滤、评分、承诺和抢占
```

内核不得依赖：

- `NpcBase`；
- `ServerLevel`；
- 方块扫描；
- 容器修改；
- 网络包；
- 客户端类。

允许使用 UUID、`ResourceLocation`、不可变集合和值对象。

## 3. 决策上下文

`NpcDecisionContext` 是一次决策的不可变输入，至少包含：

- NPC UUID；
- 服务端游戏时间；
- 可复现的随机种子；
- 该 NPC 的主观感知快照；
- 已满足的前置条件键；
- 从需求、人格、义务、关系、价值观和记忆聚合出的效用修正。

上下文不会直接查询全局状态。阶段 2 的 `NpcContextAssembler` 负责从领域服务生成它。

## 4. 候选行为

候选键必须稳定且描述具体意图，例如：

```text
blacksmith:forge:order-17
farmer:harvest:field-east:block-12-64-9
household:deliver-food:family-4
church:charity:request-23
ambient:wait:forge-coal-request-8
```

禁止只使用 `WORK`、`SOCIAL` 等粗粒度键作为承诺身份。

每个候选包含：

- Provider、计划类型、优先级和执行器；
- 对象、目标和参数；
- 情境收益、风险、体力、时间和机会成本；
- 最早/最迟开始时间与预计持续时间；
- 前置条件和硬性阻塞；
- 是否属于紧急候选。

Provider 只提出候选，不得修改世界或预留资源。

## 5. 过滤顺序

调度器按固定顺序过滤：

1. 候选数据不合法：构造时直接拒绝；
2. 存在硬性阻塞：`HARD_BLOCKER`；
3. 尚未到最早开始时间：`TOO_EARLY`；
4. 已超过最迟开始时间：`EXPIRED`；
5. 缺少已声明前置条件：`MISSING_PREREQUISITE`；
6. 现有计划仍处于承诺锁且新候选不能合法抢占：`COMMITMENT_LOCKED`。

每个拒绝都有稳定代码和人类可读说明。普通玩家不会直接看到这些后台代码。

## 6. 评分与可复现性

第一版基础效用：

```text
situationBenefit
+ context.utilityModifier[planKind]
- max(0, risk)
- max(0, effortCost)
- max(0, opportunityCost)
```

调度器再加入：

- 优先级层权重；
- 紧急候选加权；
- 基于 `randomSeed + candidateKey` 的小幅确定性噪声；
- 当前具体计划的连续性奖励。

相同上下文、候选集合和种子必须得到相同结果。候选输入顺序不得改变选择结果。

噪声只用于接近候选之间的有限理性差异，不能让休闲越过迫近生命危险，也不能绕开权限、物资或地点等硬约束。

## 7. 抢占规则

现有计划未终结且仍处于 `commitmentUntil` 前时：

- 普通同级或更低级候选不能抢占；
- 更高优先级也必须满足明确中断规则；
- `urgent=true` 不是万能通行证，仍需没有硬性阻塞；
- 当前计划位于原子结算边界时，抢占延迟到结算完成；
- 抢占不是覆盖：必须先取消/暂停旧计划并处理预留，再激活新计划。

阶段 1 只实现纯逻辑抢占判定；阶段 2 才执行实体侧暂停和恢复。

## 8. 计划状态机

```text
SELECTED → RESERVING → TRAVELLING → ACTING → SETTLING → SUCCEEDED
                │           │          │
                └──────────→ BLOCKED ←─┘
                              ↕
                           WAITING

任意非终态 → FAILED / CANCELLED
```

要求：

- 终态不可再次转换；
- 所有转换更新时间并保留计划 ID；
- `BLOCKED` 必须有 `NpcPlanBlocker`；
- 离开 `BLOCKED` 时必须显式清除或替换 blocker；
- `currentStepIndex` 不能越界；
- 每一步有独立状态和更新时间；
- 计划完成前必须完成或正式取消全部步骤。

## 9. 单活动计划

`NpcPlanStore` 对每个 NPC 最多保存一个非终态活动计划。

- 新计划不能静默覆盖旧计划；
- 旧计划必须先进入终态或由调度器完成合法抢占；
- 终态计划进入有限历史；
- 历史容量固定，防止无限增长；
- 阶段 1 使用内存实现，阶段 2/迁移阶段增加 SavedData 实现；
- Store 接口不能暴露可直接修改的内部集合。

## 10. 预留

预留键由类型和稳定资源 ID 构成，例如：

```text
workstation / overworld:12,64,9
harvest_target / overworld:30,63,44
conversation_partner / character-uuid
seat / tavern-table-2-seat-1
```

第一版预留规则：

- 同一键同一时刻只能由一个计划持有；
- 同一计划重复 acquire 幂等成功；
- 另一个计划 acquire 返回冲突，不偷占；
- 计划进入终态必须 `releasePlan`；
- 到期预留可以清理；
- 清理结果可审计，不能只返回布尔值而丢失持有人信息。

资产数量预留仍由现有 Rights/Production/Transfer 领域服务负责。阶段 1 的预留服务主要管理工作站、方块目标、人物和座位等互斥资源，避免重复实现物权账本。

## 11. 执行器契约

执行器通过 `ResourceLocation` 注册，接收不可变计划步骤和最小执行上下文。

它只返回：

- `RUNNING`；
- `STEP_SUCCEEDED`；
- `BLOCKED`，必须附 blocker；
- `FAILED`。

执行器不得直接选择下一计划。它只能报告当前步骤的结果，由调度器和计划状态机决定后继行为。

重复执行器 ID 必须拒绝；Registry 快照不可修改；取消原因使用结构化枚举。

## 12. 阶段 1 测试

必须覆盖：

1. 所有上下文和候选集合不可从外部修改；
2. 非法时间窗、NaN/Infinity、空键在构造时拒绝；
3. 硬阻塞和缺少前置条件产生正确拒绝码；
4. 相同种子和候选得到相同选择；
5. 改变候选输入顺序不改变选择；
6. 承诺锁阻止普通抢占；
7. 合法紧急候选可以触发抢占判定；
8. 非法状态转换和终态再转换被拒绝；
9. 一个 NPC 不能拥有两个活动计划；
10. 两个 NPC/计划竞争同一预留时只有一个成功；
11. 计划取消后 `releasePlan` 释放全部预留；
12. 过期清理不会删除仍有效的预留；
13. 执行器重复注册失败；
14. `BLOCKED` 结果没有 blocker 时构造失败。

## 13. 阶段出口

阶段 1 只有在以下条件全部满足后完成：

- 公共值对象和接口有中文设计注释；
- 所有集合防御复制；
- 状态转换和抢占规则有自动化测试；
- 调度结果可解释且可复现；
- 取消计划能够释放模拟预留；
- 完整项目构建和全部现有测试通过；
- 没有修改 `NpcBase.registerGoals()`、玩家 UI 或旧世界默认行为。

