package com.jgeted.sagadyssey.core;

/**
 * 已退出当前玩法设计、但仍需为旧存档保留数据定义的功能开关。
 *
 * <p>旧研究/科技点系统暂时停用。不要删除其 Attachment、Codec 或旧类；否则已有存档中的
 * {@code sagadyssey:research_points} 与 {@code sagadyssey:unlocked_research} 数据可能无法安全读取。
 * 新的知识与社会发展系统稳定并完成存档迁移前，本开关必须保持关闭。</p>
 */
public final class LegacyFeatureFlags {
    public static final boolean LEGACY_RESEARCH_ENABLED = false;

    private LegacyFeatureFlags() {
    }
}
