# 超平坦世界结构生成问题

## 问题

超平坦世界中，`/locate structure` 无法找到 sagadyssey 的任何自定义结构。

## 根因

`ChunkGeneratorStructureState.createForFlat()` 使用 `hasBiomesForStructureSet()` 过滤结构集。超平坦世界的 `possibleBiomes()` 只包含 1 个 biome（如 `minecraft:plains`），导致 25 个原版结构集中有 23 个被过滤（仅保留 2 个）。

同样的过滤也排除了所有 5 个 sagadyssey 结构集，即使 biome 配置中包含 `minecraft:plains`。Mixin 注入 `hasBiomesForStructureSet` 的修复未生效（该方法是 `private static`，Mixin 注入有特殊限制）。

## 影响

**几乎为零。** 超平坦世界本身排除大部分原版结构（23/25），主要用于创造模式建造和红石测试。正常玩法使用普通世界，结构生成完全正常。

## 状态

暂不修复。普通世界（`createForNormal`）完全正常。
