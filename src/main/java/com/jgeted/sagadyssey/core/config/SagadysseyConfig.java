package com.jgeted.sagadyssey.core.config;

import com.jgeted.sagadyssey.core.LegacyFeatureFlags;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Sagadyssey 配置文件。
 * 玩家可以在游戏内 Mods 菜单或 config 文件夹中修改。
 */
public class SagadysseyConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue NORMAL_ADVANCEMENT_POINTS;
    public static final ModConfigSpec.IntValue CHALLENGE_ADVANCEMENT_POINTS;
    public static final ModConfigSpec.BooleanValue ENABLE_DEBUG_LOG;
    public static final ModConfigSpec.DoubleValue NPC_SPAWN_CHANCE;

    /** NPC 出生带坐骑概率（默认 50%） */
    public static final ModConfigSpec.DoubleValue MOUNT_SPAWN_CHANCE;

    /** 坐骑跟随范围 */
    public static final ModConfigSpec.IntValue MOUNT_FOLLOW_RANGE;

    /** 坐骑自动上下马间隔（tick） */
    public static final ModConfigSpec.IntValue MOUNT_CHECK_INTERVAL;

    public static final ModConfigSpec.IntValue SOCIETY_SURPLUS_TICKS, SOCIETY_DECLINE_TICKS, SOCIETY_RECOVERY_TICKS, SOCIETY_FOOD_SURPLUS;
    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // 旧研究配置也不再出现在玩家配置界面；重新启用总开关时可一并恢复原键名。
        if (LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED) {
            builder.comment("研究系统设置").push("research");
            NORMAL_ADVANCEMENT_POINTS = builder
                    .comment("完成绿框进度（普通/目标）获得的研究点数")
                    .defineInRange("normalAdvancementPoints", 2, 1, 20);
            CHALLENGE_ADVANCEMENT_POINTS = builder
                    .comment("完成紫框进度（挑战）获得的研究点数")
                    .defineInRange("challengeAdvancementPoints", 5, 1, 50);
            builder.pop();
        } else {
            NORMAL_ADVANCEMENT_POINTS = null;
            CHALLENGE_ADVANCEMENT_POINTS = null;
        }

        // ===== 调试 =====
        builder.comment("调试选项").push("debug");
        ENABLE_DEBUG_LOG = builder
                .comment("开启详细日志输出，开发阶段建议开启")
                .define("enableDebugLog", true);
        builder.pop();

        // ===== NPC =====
        builder.comment("NPC 生成设置").push("npc");
        NPC_SPAWN_CHANCE = builder
                .comment("NPC 在世界中自然生成的概率 (0.0 = 不生成, 1.0 = 最高)")
                .defineInRange("spawnChance", 0.05, 0.0, 1.0);
        builder.pop();

        // ===== 坐骑系统 =====
        builder.comment("坐骑系统设置").push("vehicle");
        MOUNT_SPAWN_CHANCE = builder
                .comment("NPC 出生时附带坐骑的概率 (0.0-1.0)，测试阶段默认 0.5")
                .defineInRange("mountSpawnChance", 0.5, 0.0, 1.0);
        MOUNT_FOLLOW_RANGE = builder
                .comment("NPC 骑马跟随主人的追踪范围（格）")
                .defineInRange("mountFollowRange", 48, 16, 128);
        MOUNT_CHECK_INTERVAL = builder
                .comment("自动上下马检测间隔（tick），20 tick = 1 秒")
                .defineInRange("mountCheckInterval", 40, 10, 200);
        builder.pop();

        builder.comment("版本化自然聚落的兴衰观察阈值；已建成设施不会因改配置重复领取材料").push("society");
        SOCIETY_SURPLUS_TICKS=builder.defineInRange("surplusObservationTicks",48000,24000,960000);
        SOCIETY_DECLINE_TICKS=builder.defineInRange("shortageObservationTicks",48000,24000,960000);
        SOCIETY_RECOVERY_TICKS=builder.defineInRange("recoveryObservationTicks",24000,12000,960000);
        SOCIETY_FOOD_SURPLUS=builder.defineInRange("foodSurplusBeforeDonation",20,20,256);
        builder.pop();
        SPEC = builder.build();
    }
}
