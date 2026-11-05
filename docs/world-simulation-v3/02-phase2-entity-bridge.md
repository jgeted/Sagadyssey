# 阶段 2：实体桥、持久化与只读观察

## 1. 已完成范围

阶段 2 已把阶段 1 的纯逻辑计划接到服务端 NPC，但没有把旧职业规则伪装成新计划。

- `NpcBrainData` 以独立 schema 保存单 NPC 行为模式、活动计划和最近历史；
- 未知 NPC 和旧存档默认 `LEGACY`；
- `NpcPlanExecutionGoal` 只在管理员把单个 NPC 切换为 `PLANNED` 后参与；
- `NpcPlanExecutionService` 统一推进多步骤、阻塞、重试、失败、取消与完成；
- 已持久化的 `RUNNING` 步骤必须先调用执行器 `recover`，不会假装旧内存状态仍存在；
- 计划进入终态时统一释放互斥资源；
- 管理员命令可以查看后台真值，普通玩家载荷没有新增这些字段；
- `wait_until` 是无世界副作用的诊断执行器，用于确认保存、重载和实体桥。

阶段 2 不声称铁匠、农民或工人已经迁移。`PLANNED` NPC 没有活动计划时会明确等待，超过
200 tick 会写入服务端告警，而不是偷偷恢复旧职业 Goal。

## 2. 双轨规则

| 模式 | 行为来源 | 默认值 | 用途 |
|---|---|---:|---|
| `LEGACY` | 现有职业与生活 Goal | 是 | 现有世界正常游玩 |
| `PLANNED` | 单一 `NpcPlanExecutionGoal` | 否 | 按 NPC 灰度迁移与测试 |

战斗、低血量撤退和基础生存仍可临时抢占计划桥；旧睡眠、工作、物流、商人补货、自动坐骑和
环境动作不会在 `PLANNED` 模式并行结算。战斗结束后，未取消的活动计划继续执行。

切回 `LEGACY` 时，活动计划会正式取消并释放计划资源，不会只隐藏状态。

## 3. 管理员命令

所有命令需要权限等级 2，目标参数使用实体选择器：

```text
/saga npc brain inspect <target>
/saga npc brain history <target>
/saga npc brain reservations <target>
/saga npc brain mode <target> legacy
/saga npc brain mode <target> planned
/saga npc brain seed-wait <target> <ticks>
```

`inspect/history/reservations` 只读。`mode` 和 `seed-wait` 是显式测试操作。

最小验证流程：

1. 保持 NPC 为 `LEGACY`，确认旧行为不变；
2. 执行 `mode ... planned`，确认 NPC 不再运行旧职业行为；
3. 执行 `seed-wait ... 200`；
4. 用 `inspect` 观察 `SELECTED → ACTING/RUNNING → SUCCEEDED`；
5. 在等待中保存并退出，再进入世界，确认同一计划继续而不是复制；
6. 用 `history` 确认计划只有一个终态记录；
7. 切回 `legacy`，确认旧行为恢复。

## 4. 持久化边界

- 数据名：`sagadyssey_npc_brain_v3`；
- schema：`1`；
- 每个 NPC 最多一个非终态计划；
- 每个 NPC 最多保存 32 条终态历史；
- NBT 单计划最多 64 个步骤，每步最多 64 个参数；
- 未知枚举、非法资源 ID、重复参数和坏计划会被拒绝；
- 单条坏历史不会阻止其他 NPC 或其他历史恢复；
- 未知 schema 安全回退为空数据和 `LEGACY`，不猜测迁移。

## 5. 阶段出口

- 全部自动化测试通过：195 项，0 失败；
- 完整 `gradlew build` 通过；
- 默认旧世界行为不变；
- 新模式与旧非战斗行为不双跑；
- 活动计划、步骤、阻塞、历史和资源保留可由管理员只读观察；
- 下一阶段可以在不改状态机的前提下接入铁匠的真实订单与执行器。
