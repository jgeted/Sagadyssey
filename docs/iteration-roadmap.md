# Sagadyssey 建筑生成系统 — 迭代路线图

## 当前进度 (v1.1)

| 项目 | 状态 |
|------|------|
| 基础架构（jigsaw + datapack JSON） | ✅ |
| 诊断命令 `/saga structures dump` | ✅ |
| 5 个 NBT 建筑模板 | ✅ |
| 3 类结构集生成（农舍 / 营地 / 哨塔） | ✅ |
| 间距 & biome 分流优化 | ✅ |
| 普通世界正常生成 | ✅ |
| 超平坦 | ❌ 已知限制，不阻塞 |

---

## Phase 1：建筑质量打磨

**目标**：让已有建筑不只是方块，而是活的。

| 任务 | 说明 |
|------|------|
| NBT 模板完善 | 检查内饰、材料 palette、结构完整性 |
| 战利品表 | 每个建筑添加 loot table（农舍食物/工具、营地武器/金币、哨塔装备） |
| 生物生成 | camp_bandit 生成掠夺者、house_cottage 生成村民/NPC |
| 处理器 | 用 processor_list 替换部分方块（如苔石、裂缝）增加年代感 |

**依赖**：无  
**产出**：5 个高品质、有交互的中世纪建筑

---

## Phase 2：村庄聚落系统

**目标**：不是单个房子，而是聚落。

| 任务 | 说明 |
|------|------|
| 研究原版村庄 jigsaw 布局 | 中心广场 → 道路 → 房屋 → 装饰 的连接规则 |
| 制作 village_center NBT | 水井 / 小广场 + 钟 |
| 制作 road NBT | 土路 / 碎石路 piece |
| jigsaw 连接 | center→road→house 的 target pool 链 |
| 绑定 NPC 生成 | 村民/NPC 自然出现在聚落内 |
| 制作 2-3 个村庄变体 | 平原村、森林村、山地村（复用建筑+不同 palette） |

**依赖**：Phase 1 完成（建筑质量到位）  
**产出**：一个真正的聚落系统

---

## Phase 3：原版建筑替换

**目标**：用中世纪建筑替代所有原版结构。

### 有对应建筑的替换

| 原版 | 替换为 | 新建/复用 |
|------|--------|----------|
| pillager_outpost | bandit_keep（强盗要塞） | 新建（camp_bandit 的升级版） |
| swamp_hut | hermit_hut（隐士草屋） | 新建 |
| woodland_mansion | lord_castle（领主城堡） | 新建（大型） |
| igloo | hunter_lodge（猎人小屋） | 新建 |

### 无对应但应替换的

| 原版 | 替换为 | 说明 |
|------|--------|------|
| desert_pyramid | desert_tomb | 保留探索感 |
| jungle_pyramid | jungle_ruins | 保留探索感 |
| shipwreck | wrecked_galley | 中世纪沉船 |
| ruined_portal | ancient_gate | 中世纪遗迹 |
| trail_ruins | medieval_ruins | 中世纪废墟 |
| ocean_monument | 不做替换 | 保持深海独特体验 |

**依赖**：Phase 1 完成  
**产出**：完全中世纪化的世界

---

## Phase 4：中世纪世界填充

**目标**：添加原版没有的纯新增建筑，填满中世纪世界。

| 建筑 | spacing | biome | 定位 |
|------|---------|-------|------|
| windmill | 40 | plains, meadow | 平原风车 |
| waystation | 48 | 沿路 biome | 道路驿站 |
| monastery | 80 | mountain 边缘 | 山顶修道院 |
| stone_bridge | 60 | river | 跨河石桥 |
| graveyard | 24 | 各种 | 小墓地 |
| fisher_hut | 16 | river, beach | 渔夫小屋 |
| lumber_camp | 32 | forest | 伐木营地 |
| merchant_camp | 60 | plains | 商队营地 |
| quarry | 80 | hills | 采石场 |
| shrine | 20 | 各种 | 路边神龛 |

**依赖**：Phase 3 完成（避免与新替换结构冲突）  
**产出**：丰富的中世纪世界

---

## Phase 5：地形感知生成

**目标**：建筑不只是"放在 biome 里"，而是"选对位置"。

| 任务 | 说明 |
|------|------|
| 自定义 StructurePlacement | 侦测 terrain slope、water proximity、elevation |
| 哨塔放高地 | 检测 Y 轴最高点附近再放置 |
| 桥跨河流 | 检测 river biome + 宽度来生成 |
| 渔村靠海 | 检测 ocean/beach 近旁 |

**依赖**：需要 Java 代码（自定义 placement），不只是纯 JSON  
**产出**：沉浸式地形适配

---

## 优先级总结

```
Phase 1 (建筑质量) → 当前立即可做，影响游戏体验最直接
Phase 2 (村庄聚落) → 核心系统，定义"中世纪"感
Phase 3 (原版替换) → 需要新建建筑，和工作量匹配
Phase 4 (世界填充) → 纯新增，在替换完成后做
Phase 5 (地形感知) → 锦上添花，最后做
```
