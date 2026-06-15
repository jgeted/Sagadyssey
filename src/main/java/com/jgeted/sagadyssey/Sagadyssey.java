package com.jgeted.sagadyssey;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Sagadyssey 模组主类。
 * 中世纪主题 Minecraft 扩展——NPC 聚落、蓝图建造、坐骑增强。
 */
@Mod(Sagadyssey.MOD_ID)
public class Sagadyssey {
    public static final String MOD_ID = "sagadyssey";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Sagadyssey(IEventBus modEventBus) {
        LOGGER.info("Sagadyssey 初始化开始");
    }
}
