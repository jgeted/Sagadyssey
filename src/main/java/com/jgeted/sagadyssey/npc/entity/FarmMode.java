package com.jgeted.sagadyssey.npc.entity;

/**
 * 农民务农模式。
 * CROP — 只种农作物：翻地、轮作播种、收获（不种甘蔗、不挖沟灌溉）
 * MELON — 只种瓜类（西瓜/南瓜）：收果实、种茎、翻地
 * CANE — 只种甘蔗：水边种植、按需挖水源、两桶水建无限水井
 * COCOA — 只种可可豆：种原木柱 + 贴可可豆 + 收获
 * AUTO — 综合（默认）：维护普通作物、瓜类、已有水边甘蔗和已有原木上的可可豆
 */
public enum FarmMode {
    CROP,
    MELON,
    CANE,
    COCOA,
    AUTO;

    /** 界面显示名 */
    public String getDisplayName() {
        return switch (this) {
            case CROP -> "种植农作物";
            case MELON -> "种瓜类";
            case CANE -> "种植甘蔗";
            case COCOA -> "种植可可豆";
            case AUTO -> "综合";
        };
    }
}
