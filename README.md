# Sagadyssey（史诗远行）

一个中世纪主题的 Minecraft 1.21.1 扩展 Mod，基于 NeoForge 构建。

> **当前阶段：** 微模组验证（一个方块 + 一个 GUI）
> **GitHub：** https://github.com/jgeted/Sagadyssey

---

## 📥 下载与安装（玩家）

### 前提条件

1. 安装 **Minecraft 1.21.1**（Java Edition）
2. 安装 **NeoForge 21.1.233**（下载地址：[NeoForge 官网](https://neoforged.net/)）

### 安装步骤

1. 从 [Releases 页面](https://github.com/jgeted/Sagadyssey/releases) 下载最新版本的 `.jar` 文件
2. 将下载的 `.jar` 文件放入 Minecraft 的 `mods` 文件夹：
   - **Windows:** `%APPDATA%\.minecraft\mods`
   - **macOS:** `~/Library/Application Support/minecraft/mods`
   - **Linux:** `~/.minecraft/mods`
3. 启动 NeoForge 1.21.1 客户端即可游玩

### 没有 Release？

如果 GitHub Releases 中还没有发布版本，你可以从 GitHub Actions 的构建产物中获取：

1. 打开仓库的 [Actions 页面](https://github.com/jgeted/Sagadyssey/actions)
2. 点击最新的成功构建（绿色勾 ✓）
3. 在页面底部的 **Artifacts** 区域下载 `.jar` 文件

---

## 🛠 构建与开发（开发者）

### 环境要求

| 工具 | 版本 |
|------|------|
| JDK | **21**（推荐 Eclipse Adoptium / Temurin） |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233 |
| Gradle | 随项目自带（`gradlew` / `gradlew.bat`） |

### 克隆与构建

```bash
# 克隆仓库
git clone https://github.com/jgeted/Sagadyssey.git
cd Sagadyssey

# 编译构建
./gradlew build

# 构建产物在 build/libs/sagadyssey-1.0.0.jar
```

### 运行客户端

```bash
./gradlew runClient
```

构建完成后会自动启动 Minecraft，Mod 会自动加载。

### 国内网络设置

如果 `maven.neoforged.net` 无法访问（SSL 握手失败/连接重置），请参考 `CLAUDE.md` 中的代理配置方法。

---

## 📦 发布新版（维护者）

### 通过 GitHub Release 发布

```bash
# 1. 构建
./gradlew build

# 2. 打标签并推送
git tag v1.0.0
git push origin v1.0.0

# 3. 在 GitHub 上创建 Release：
#    - 前往 https://github.com/jgeted/Sagadyssey/releases
#    - 点击 "Create a new release"
#    - 选择刚推送的标签
#    - 从 build/libs/ 上传 .jar 文件
#    - 填写版本说明，点击发布
```

你也可以在 Actions 页面下载每次提交的构建产物（自动上传）。

---

## 📁 项目结构

| 模块 | 说明 | 状态 |
|------|------|------|
| **Core** | 网络、配置、研究/技能树 | 进行中 |
| **NPC** | 中世纪 NPC、招募、职业、阵营 | 计划中 |
| **Structure** | 世界生成营地/定居点、蓝图建造 | 计划中 |
| **Vehicle** | 增强马/驴/狗/船 AI | 计划中 |

---

## 📄 许可

All Rights Reserved
