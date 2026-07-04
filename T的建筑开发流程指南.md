# T 的建筑开发流程指南

## 一、环境搭建（只需做一次）

### 1. 安装必要软件

- **Git**：https://git-scm.com/downloads
- **JDK 21**：https://adoptium.net/ （下载 Eclipse Temurin 21，一路下一步安装即可）
- **PCL 启动器**：你应该已经有了
- **VS Code**（可选，用来编辑 JSON 配置文件）：https://code.visualstudio.com/

### 2. Clone 项目仓库

打开 Git Bash（安装 Git 后右键菜单里会有），执行：

```bash
# 选一个你喜欢的文件夹，比如桌面
cd ~/Desktop

# 克隆仓库
git clone https://github.com/jgeted/Sagadyssey.git

# 进入项目目录
cd Sagadyssey
```

完成后你会得到一个 `Sagadyssey` 文件夹，里面就是全部源码和资源文件。

### 3. 在 PCL 中配置本地测试环境

1. 打开 PCL → 版本设置 → 安装新版本
2. 选择 Minecraft **1.21.1**
3. 版本类型选 **NeoForge**（选最新的 NeoForge 版本）
4. 点击安装，等待下载完成
5. 安装完成后，**不要急着启动**，先把 mod 放进去：
   - 在 PCL 中右键该版本 → 打开版本文件夹
   - 找到或新建 `mods` 文件夹
   - 去 GitHub Releases（https://github.com/jgeted/Sagadyssey/releases）下载最新的 `.jar` 文件
   - 把 `.jar` 文件放进 `mods` 文件夹

每次 jgeted 发布新版本，你需要重新下载新的 `.jar` 替换掉旧的就行。

---

## 二、建筑文件在哪里

项目中和建筑相关的文件主要在这几个位置：

```
Sagadyssey/
└── src/main/resources/
    ├── data/sagadyssey/
    │   └── structure/              ← 结构定义文件（JSON）
    │       └── *.json
    └── assets/sagadyssey/
        └── structures/             ← 结构数据文件（NBT）
            └── *.nbt
```

> **注意**：具体路径以 jgeted 确认的为准，clone 下来后先看一下项目里的实际目录结构。如果找不到上述文件夹，说明还没创建，联系 jgeted 确认。

### 两种文件分别是什么

| 文件类型 | 格式 | 作用 | 怎么创建 |
|---------|------|------|---------|
| **NBT 结构文件** | `.nbt` | 存储你搭建的建筑的实际方块数据 | 在游戏内用结构方块保存 |
| **JSON 配置文件** | `.json` | 定义结构的生成规则（生成条件、间距、群系等） | 用文本编辑器手写 |

---

## 三、创建 NBT 结构文件（核心工作）

这是你最常做的事情——在游戏里搭建筑，然后导出成结构文件。

### 步骤

**1. 启动游戏**

用 PCL 启动配好 NeoForge + mod 的 Minecraft 版本，进入**创造模式**世界。

> 建议新开一个创造模式存档专门用来搭建，输入 `/gamemode creative` 切换。

**2. 搭建你的建筑**

用任意方块搭建你想要的建筑。注意：
- 建筑尺寸不要超过 **48×48×48** 个方块（结构方块的最大范围）
- 记住你搭建区域的**三个维度尺寸**（长、宽、高），后面要用
- 如果建筑有多个变体（比如不同大小的营地），每个变体都要单独保存

**3. 获取结构方块**

按 `T` 打开聊天栏，输入：
```
/give @s structure_block
```
回车，结构方块会出现在你的物品栏里。

再获取一个结构空方块（用于标记要排除的区域，比如空气）：
```
/give @s structure_void
```

**4. 放置结构方块并配置**

把结构方块放在建筑的**角落**（左下角），然后右键打开它：

| 设置项 | 填什么 |
|-------|-------|
| 结构名称 | 按项目规范命名，比如 `sagadyssey:camp_small`（具体命名问 jgeted） |
| 模式 | 选 **保存（Save）** |
| 相对位置 | 结构方块到建筑对角的偏移，比如建筑从方块往右 10 格、往上 8 格、往南 12 格，就填 `10 8 12` |
| 尺寸 | 建筑的长宽高，比如 `10 8 12` |
| 检测实体 | 一般**关掉**（除非建筑里需要包含NPC等实体） |
| 完整性 | **1.0**（1.0 = 保留所有方块，不要改） |
| 种子 | 留空 |

然后点击 **保存（SAVE）**。

**5. 导出 NBT 文件**

保存后，结构数据会存在存档里。你需要把它提取出来：

**方法 A：直接从存档文件夹复制**

1. 退出游戏
2. 在 PCL 里右键该版本 → 打开版本文件夹（这通常会打开 `.minecraft` 目录）
3. 进入 `saves/你的存档名/generated/` 或 `saves/你的存档名/structures/`
4. 找到你保存的 `.nbt` 文件
5. 把它复制到项目目录对应的位置：
   ```
   复制到 → Sagadyssey/src/main/resources/assets/sagadyssey/structures/
   ```

**方法 B：用结构方块导出（如果方法 A 找不到文件）**

1. 在游戏内，结构方块切换到 **加载（Load）** 模式
2. 输入结构名称
3. 这会把结构加载到世界中——说明保存成功了
4. 然后去存档文件夹里找 `.nbt` 文件，路径同上

**6. 确认文件命名**

NBT 文件命名必须和项目中的引用一致，例如：
```
camp_small.nbt       ← 小型营地
tavern_medium.nbt    ← 中型酒馆
watchtower.nbt       ← 瞭望塔
```

具体命名规范以 jgeted 的要求为准，提交前先沟通确认。

---

## 四、编辑 JSON 配置文件

JSON 文件定义的是"这个建筑怎么在世界中生成"的规则，不需要在游戏里操作，直接用文本编辑器写。

> ### 🔒 JSON 格式已锁定（2026-07-04）
>
> **以下格式为最终版本，不可更改。** jgeted 的代码将严格按照此格式解析。
> 如需新增字段，必须先和 jgeted 沟通，由 jgeted 更新本文档后才能使用。
> 未经沟通擅自修改格式的 JSON 文件会被打回。

### 完整字段定义

```jsonc
{
  // ===== 必填字段（全部必填，不能省略） =====

  "id": "camp_bandit_small",          // 唯一标识符，必须和 JSON 文件名完全一致
  "nbt": "sagadyssey:camp_bandit_small", // 对应 NBT 文件，格式 "sagadyssey:{文件名}"
  "name": "小型强盗营地",               // 中文显示名
  "category": "camp",                 // 结构类别（见下方允许值）
  "size": "small",                    // 尺寸档位（见下方允许值）
  "biomes": "#sagadyssey:has_camp",   // 群系标签，格式 "#sagadyssey:has_{category}"

  "step": "surface_structures",       // 生成阶段，固定填 "surface_structures"
  "terrain_adaptation": "beard_thin", // 地形适配，固定填 "beard_thin"

  "start_height": {                   // 生成高度范围
    "type": "uniform",                // 固定填 "uniform"
    "min_inclusive": { "absolute": 62 }, // 最低生成高度
    "max_inclusive": { "absolute": 90 }  // 最高生成高度
  },

  "spacing": 20,                      // 平均间距（区块），越大越稀疏
  "separation": 10,                   // 最小间距（区块），必须 < spacing
  "max_distance_from_center": 80,     // 距世界原点最大生成距离

  "weight": 10,                       // 生成权重（同类别中相对概率，越大越常见）
  "allow_underground": false,         // 是否允许地下生成
  "allow_water": false                // 是否允许水中生成
}
```

### 字段允许值

| 字段 | 允许值 | 说明 |
|------|--------|------|
| `category` | `"camp"` / `"house"` / `"watchtower"` | 目前仅此三种，新增需 jgeted 批准 |
| `size` | `"small"` / `"medium"` / `"large"` | 影响默认间距和高度范围 |
| `step` | `"surface_structures"` | 固定值，不要改 |
| `terrain_adaptation` | `"beard_thin"` | 固定值，不要改 |
| `start_height.type` | `"uniform"` | 固定值，不要改 |
| `allow_underground` | `false` | 固定值，不要改 |
| `allow_water` | `false` | 固定值，不要改 |

### 数值参考范围

| 参数 | small | medium | large |
|------|-------|--------|-------|
| `spacing`（平均间距） | 8-20 | 12-20 | 28-32 |
| `separation`（最小间距） | 4-10 | 6-10 | 14-16 |
| `start_height.min` | 62 | 62 | 62 |
| `start_height.max` | 90 | 90 | 100 |
| `max_distance_from_center` | 80 | 80 | 100-120 |
| `weight` | 10-30 | 20 | 3-5 |

> **约束**：`separation` 必须严格小于 `spacing`，否则游戏会报错。

### NBT 文件命名规范

格式：`{category}_{描述}_{size}.nbt`

- 全部小写，用下划线分隔
- `category` 和 `size` 必须和 JSON 中的值对应
- 描述部分用简短英文，如 `bandit`、`cottage`、`stone`

**已有的命名示例：**
```
camp_bandit_small.nbt        → JSON: "id": "camp_bandit_small"
house_cottage_small.nbt      → JSON: "id": "house_cottage_small"
house_cottage_medium.nbt     → JSON: "id": "house_cottage_medium"
house_cottage_large.nbt      → JSON: "id": "house_cottage_large"
watchtower_stone_large.nbt   → JSON: "id": "watchtower_stone_large"
```

### 完整模板（直接复制修改）

**新建 JSON 时，复制下面的模板，只改 `{...}` 里的内容：**

```json
{
  "id": "{category}_{描述}_{size}",
  "nbt": "sagadyssey:{category}_{描述}_{size}",
  "name": "{中文显示名}",
  "category": "{camp|house|watchtower}",
  "size": "{small|medium|large}",

  "biomes": "#sagadyssey:has_{category}",
  "step": "surface_structures",

  "terrain_adaptation": "beard_thin",

  "start_height": {
    "type": "uniform",
    "min_inclusive": { "absolute": 62 },
    "max_inclusive": { "absolute": 90 }
  },

  "spacing": {参考上表},
  "separation": {参考上表},
  "max_distance_from_center": 80,

  "weight": {参考上表},
  "allow_underground": false,
  "allow_water": false
}
```

### 自检清单（写完 JSON 后逐条检查）

- [ ] `id` 和 JSON 文件名完全一致（不含 `.json` 后缀）
- [ ] `nbt` = `"sagadyssey:" + id`
- [ ] `category` 是 `camp` / `house` / `watchtower` 之一
- [ ] `size` 是 `small` / `medium` / `large` 之一
- [ ] `biomes` = `"#sagadyssey:has_" + category`
- [ ] `separation` < `spacing`
- [ ] NBT 文件名和 `id` 一致：`{id}.nbt`
- [ ] JSON 语法正确（用 https://jsonlint.com 验证）

---

## 五、提交和推送

编辑完文件后，把你的改动提交到仓库：

```bash
# 1. 查看你改了哪些文件
git status

# 2. 把改动加入暂存区（. 表示全部改动）
git add .

# 3. 提交（用中文描述你做了什么）
git commit -m "添加小型营地结构文件"

# 4. 推送到远程仓库
git push
```

### 提交前检查清单

- [ ] NBT 文件已放到正确的目录
- [ ] 文件命名符合规范（小写 + 下划线）
- [ ] JSON 文件语法正确（可以在 https://jsonlint.com 验证）
- [ ] 在 PCL 测试过，建筑在游戏里能正常生成/加载
- [ ] commit message 用中文，简洁描述做了什么

---

## 六、日常更新流程

以后每次 jgeted 发了新版本：

1. **拉取最新代码**：
   ```bash
   cd ~/Desktop/Sagadyssey
   git pull
   ```
2. **下载新的 mod jar**：从 GitHub Releases 下载最新版，替换 `mods` 文件夹里的旧 jar
3. **开始工作**：搭新建筑、导出 NBT、编辑 JSON、提交推送

---

## 七、常见问题

**Q：结构方块保存时报错"结构过大"怎么办？**
A：单个结构最大 48×48×48。如果建筑更大，拆成多个子结构分别保存，或者缩小建筑尺寸。

**Q：加载结构时方块位置不对？**
A：检查结构方块的「相对位置」是否正确——它是从结构方块到建筑**对角**的偏移量。

**Q：建筑生成在半空中或地下？**
A：调整 JSON 中的 `start_height` 和 `terrain_adaptation` 参数。`beard_thin` 会自动削平地面，`none` 则完全适配原地形。

**Q：push 的时候提示冲突？**
A：先 `git pull` 拉取最新代码，如果有冲突联系 jgeted 解决。不要强制 push。

**Q：找不到 NBT 文件？**
A：Minecraft 1.21+ 中，结构方块保存的文件在存档目录的 `generated/minecraft/structures/` 下，文件名就是你填的结构名称（把冒号换成斜杠，比如 `sagadyssey:camp_small` → `generated/minecraft/structures/sagadyssey/camp_small.nbt`）。

---

## 快速参考

```bash
# 常用命令速查
git clone https://github.com/jgeted/Sagadyssey.git   # 第一次：克隆仓库
git pull                                              # 每次开始前：拉最新代码
git status                                            # 查看改动
git add .                                             # 暂存所有改动
git commit -m "描述你做了什么"                          # 提交
git push                                              # 推送到 GitHub
```

有问题随时问 jgeted，搭建部分以他的确认和规范为准。
