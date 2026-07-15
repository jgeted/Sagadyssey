# Sagadyssey 建筑替换与新增总规划

## 设计目标

中世纪模拟沉浸体验。建筑不再是"随机散落的方块"，而是构成一个可信的中世纪世界：村庄聚落、盗匪据点、哨塔警戒、荒野遗迹。

---

## 一、已有建筑 → 替换计划（当前可落地）

### 1. village_plains → 中世纪村落（house_cottage 三件套）

**方式**：新建 `sagadyssey:medieval_village` structure_set，拉入 3 个 cottage 并统一 spacing。

| 建筑 | weight | 占比 | 角色 |
|---|---|---|---|
| house_cottage_small | 30 | 60% | 平民茅屋，聚落主体 |
| house_cottage_medium | 15 | 30% | 富裕农家，村落中心附近 |
| house_cottage_large | 5 | 10% | 贵族庄园，稀有 |

| 参数 | 值 | 理由 |
|---|---|---|
| spacing | 34 | 与原版村庄一致 |
| separation | 8 | 与原版村庄一致 |
| biomes | plains, sunflower_plains, meadow | 平原型聚落 |

### 2. pillager_outpost → 强盗营地（camp_bandit_small）

**方式**：新建 `sagadyssey:bandit_encampment` structure_set。

| 参数 | 值 | 理由 |
|---|---|---|
| spacing | 48 | 与原版前哨一致 |
| separation | 24 | 避免营地重叠 |
| biomes | forest, dark_forest, savanna_plateau | 隐蔽/偏远地带 |

### 3. watchtower_stone_large → 哨塔（无原版对应，纯新增）

**方式**：新建 `sagadyssey:watchtower_network` structure_set。

| 参数 | 值 | 理由 |
|---|---|---|
| spacing | 28 | 比村庄频繁，模拟警戒网络 |
| separation | 10 | 互相可见但不过密 |
| biomes | plains, meadow, savanna, forest, windswept_hills | 开阔视野处 |

---

## 二、未来新增建筑（尚未制作，仅规划）

### 替换原版的

| 原版 | 替换建筑 | 风格 | 优先级 |
|---|---|---|---|
| swamp_hut | hermit_hut | 沼泽隐士小屋 | 低 |
| desert_pyramid | desert_tomb | 沙漠陵墓 | 低 |
| jungle_pyramid | jungle_ruins | 丛林遗迹 | 低 |
| woodland_mansion | lord_castle | 石质领主城堡 | 高 |
| igloo | hunter_lodge | 雪原猎屋 | 低 |
| ocean_monument | sunken_fortress | 沉没要塞 | 低 |
| ruined_portal | ancient_gate | 古代传送门废墟 | 中 |
| shipwreck | wrecked_galley | 中世纪沉船 | 中 |
| trail_ruins | medieval_ruins | 中世纪废墟 | 中 |

### 纯新增（原版无对应）

| 建筑 | 定位 | 频率 |
|---|---|---|
| windmill | 风车磨坊，平原标识物 | 常见 |
| waystation | 道路驿站 | 常见 |
| monastery | 山顶修道院 | 不常见 |
| stone_bridge | 石桥 | 河流 |
| graveyard | 小墓地 | 常见 |
| merchant_camp | 商队营地 | 不常见 |
| quarry | 采石场 | 不常见 |
| fisher_hut | 渔夫小屋 | 河流/海岸 |
| lumber_camp | 伐木营地 | forest |
| shrine | 路边神龛 | 常见 |
