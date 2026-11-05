package com.jgeted.sagadyssey;

import com.jgeted.sagadyssey.client.TestScreen;
import com.jgeted.sagadyssey.core.LegacyFeatureFlags;
import com.jgeted.sagadyssey.core.command.NpcSpawnCommand;
import com.jgeted.sagadyssey.core.command.ResearchCommand;
import com.jgeted.sagadyssey.core.command.StructureDiagnosticCommand;
import com.jgeted.sagadyssey.core.command.StructurePreviewCommand;
import com.jgeted.sagadyssey.core.command.TestNetworkCommand;
import com.jgeted.sagadyssey.core.config.SagadysseyConfig;
import com.jgeted.sagadyssey.core.network.SagadysseyNetworking;
import com.jgeted.sagadyssey.core.research.ResearchAttachments;
import com.jgeted.sagadyssey.core.research.ResearchRegistry;
import com.jgeted.sagadyssey.core.registry.ModMenuTypes;
import com.jgeted.sagadyssey.core.structure.SagadysseyStructures;
import com.jgeted.sagadyssey.npc.event.NpcExperienceEvents;
import com.jgeted.sagadyssey.npc.event.NpcFactionEvents;
import com.jgeted.sagadyssey.npc.event.NpcOwnerCombatEvents;
import com.jgeted.sagadyssey.npc.command.CommandGroupAttachments;
import com.jgeted.sagadyssey.npc.command.NpcWorkCommand;
import com.jgeted.sagadyssey.npc.command.NpcLifeCommand;
import com.jgeted.sagadyssey.npc.brain.debug.NpcBrainCommand;
import com.jgeted.sagadyssey.npc.brain.execution.NpcBrainEvents;
import com.jgeted.sagadyssey.npc.faction.FactionAttachments;
import com.jgeted.sagadyssey.npc.faction.FactionRegistry;
import com.jgeted.sagadyssey.npc.faction.StandingDecayHandler;
import com.jgeted.sagadyssey.npc.identity.PlayerIdentityAttachments;
import com.jgeted.sagadyssey.npc.identity.command.PlayerIdentityCommand;
import com.jgeted.sagadyssey.npc.faction.command.FactionCommand;
import com.jgeted.sagadyssey.npc.faction.util.FactionDataLoader;
import com.jgeted.sagadyssey.npc.gui.NpcEquipScreen;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.registry.ModBlocks;
import com.jgeted.sagadyssey.registry.ModCreativeTabs;
import com.jgeted.sagadyssey.registry.ModItems;
import com.jgeted.sagadyssey.registry.ModMenus;
import com.jgeted.sagadyssey.vehicle.VehicleEventHandler;
import com.jgeted.sagadyssey.vehicle.command.MountCommand;
import com.jgeted.sagadyssey.social.character.CharacterLifecycleEvents;
import com.jgeted.sagadyssey.social.world.WorldSocialEvents;
import com.jgeted.sagadyssey.social.world.WorldStateCommand;
import com.jgeted.sagadyssey.social.politics.PoliticalCommand;
import com.jgeted.sagadyssey.social.logistics.LogisticsCommand;
import com.jgeted.sagadyssey.social.logistics.NpcLogisticsEvents;
import com.jgeted.sagadyssey.social.economy.SettlementEconomyCommand;
import com.jgeted.sagadyssey.social.economy.SettlementEconomyEvents;
import com.jgeted.sagadyssey.social.world.WorldDynamicsCommand;
import com.jgeted.sagadyssey.social.world.WorldDynamicsEvents;
import com.jgeted.sagadyssey.social.SocialSystemAuditCommand;
import com.jgeted.sagadyssey.social.scenario.FixedScenarioCommand;
import com.jgeted.sagadyssey.social.scenario.FixedScenarioEvents;
import com.jgeted.sagadyssey.social.life.NpcContractEvents;
import com.jgeted.sagadyssey.social.household.HouseholdCommand;
import com.jgeted.sagadyssey.social.household.HouseholdCareEvents;
import com.jgeted.sagadyssey.worldsim.WorldSimulationCommand;
import com.jgeted.sagadyssey.worldsim.verifier.VerifierCommand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Sagadyssey 模组主类。
 * 中世纪主题 Minecraft 扩展——NPC 聚落、蓝图建造、坐骑增强。
 */
@Mod(Sagadyssey.MOD_ID)
public class Sagadyssey {
    public static final String MOD_ID = "sagadyssey";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Sagadyssey(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Sagadyssey 初始化开始");

        // 注册配置文件
        modContainer.registerConfig(ModConfig.Type.COMMON, SagadysseyConfig.SPEC);

        // 客户端：注册配置界面扩展点（Mods 菜单里的 Config 按钮）
        if (FMLLoader.getDist() == Dist.CLIENT) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }

        // 注册所有内容
        ModBlocks.REGISTRY.register(modEventBus);
        ModItems.REGISTRY.register(modEventBus);
        ModMenuTypes.REGISTRY.register(modEventBus);
        ModMenus.REGISTRY.register(modEventBus);
        ModCreativeTabs.REGISTRY.register(modEventBus);

        // 始终注册旧研究 Attachment，以便已有存档仍能反序列化并原样保存历史数据。
        // 玩家入口和数据写入由 LegacyFeatureFlags 关闭。
        ResearchAttachments.ATTACHMENT_TYPES.register(modEventBus);

        // 注册 Attachment（阵营声望数据）
        FactionAttachments.ATTACHMENT_TYPES.register(modEventBus);

        // 注册玩家集团、小队和指挥等级数据
        CommandGroupAttachments.ATTACHMENT_TYPES.register(modEventBus);

        // 注册独立的玩家社会阶层与职务数据
        PlayerIdentityAttachments.ATTACHMENT_TYPES.register(modEventBus);

        // 注册 NPC 实体 + 生成蛋
        NpcEntityTypes.REGISTRY.register(modEventBus);
        NpcEntityTypes.SPAWN_EGGS.register(modEventBus);
        modEventBus.addListener(NpcEntityTypes::registerAttributes);
        LOGGER.info("NPC 实体已注册");

        if (LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED) {
            ResearchRegistry.init();
            LOGGER.info("旧技能树已加载（{} 个节点）", ResearchRegistry.getAllNodes().size());
        }

        // 注册网络数据包
        modEventBus.register(SagadysseyNetworking.class);

        // 注册阵营 Registry（数据驱动 DataPackRegistry，mod bus）
        modEventBus.register(FactionRegistry.class);

        LOGGER.info("网络通信已加载");

        // 注册命令
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> {
            if (LegacyFeatureFlags.LEGACY_RESEARCH_ENABLED) {
                TestNetworkCommand.register(event.getDispatcher());
                ResearchCommand.register(event.getDispatcher());
            }
            NpcSpawnCommand.register(event.getDispatcher());
            NpcBrainCommand.register(event.getDispatcher());
            NpcWorkCommand.register(event.getDispatcher());
            NpcLifeCommand.register(event.getDispatcher());
            HouseholdCommand.register(event.getDispatcher());
            com.jgeted.sagadyssey.social.settlement.SettlementMemberCommand.register(event.getDispatcher());
            com.jgeted.sagadyssey.social.growth.SettlementCoordinationCommand.register(event.getDispatcher());
            PlayerIdentityCommand.register(event.getDispatcher());
            StructureDiagnosticCommand.register(event.getDispatcher());
            StructurePreviewCommand.register(event.getDispatcher());
            FactionCommand.register(event.getDispatcher());
            MountCommand.register(event.getDispatcher());
            WorldStateCommand.register(event.getDispatcher());
            PoliticalCommand.register(event.getDispatcher());
            LogisticsCommand.register(event.getDispatcher());
            SettlementEconomyCommand.register(event.getDispatcher());
            WorldDynamicsCommand.register(event.getDispatcher());
            SocialSystemAuditCommand.register(event.getDispatcher());
            FixedScenarioCommand.register(event.getDispatcher());
            WorldSimulationCommand.register(event.getDispatcher());
            com.jgeted.sagadyssey.worldsim.lod.NpcLodCommand.register(event.getDispatcher());
            VerifierCommand.register(event.getDispatcher());
            LOGGER.info("命令已注册: /saga npc spawn, /saga identity, /saga structures dump, /saga structure preview, /saga structure gallery, /sagadyssey faction, /saga mount");
        });

        // 注册 NPC 经验事件
        NeoForge.EVENT_BUS.register(NpcExperienceEvents.class);
        NeoForge.EVENT_BUS.register(NpcBrainEvents.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.worldsim.lod.NpcLodEvents.class);
        NeoForge.EVENT_BUS.register(NpcOwnerCombatEvents.class);
        NeoForge.EVENT_BUS.register(CharacterLifecycleEvents.class);
        NeoForge.EVENT_BUS.register(WorldSocialEvents.class);
        NeoForge.EVENT_BUS.register(NpcLogisticsEvents.class);
        NeoForge.EVENT_BUS.register(SettlementEconomyEvents.class);
        NeoForge.EVENT_BUS.register(WorldDynamicsEvents.class);
        NeoForge.EVENT_BUS.register(FixedScenarioEvents.class);
        NeoForge.EVENT_BUS.register(NpcContractEvents.class);
        NeoForge.EVENT_BUS.register(HouseholdCareEvents.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.settlement.NaturalStorageAccessEvents.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.needs.SettlementExchangeData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.household.NaturalHouseholdMigrationData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.household.ResidentWorkMigrationData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.politics.NaturalPolityData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.growth.SettlementGrowthData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.growth.SettlementCoordinatorData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.growth.SettlementDevelopmentData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.growth.SettlementSafetyData.class);
        NeoForge.EVENT_BUS.register(com.jgeted.sagadyssey.social.combat.NaturalConflictData.class);

        // 注册阵营声望事件处理器（击杀、睡眠、涟漪重置等）
        NeoForge.EVENT_BUS.register(new NpcFactionEvents());

        // 注册阵营声望衰减处理器
        NeoForge.EVENT_BUS.register(new StandingDecayHandler());

        // 注册 Vehicle 事件处理器
        NeoForge.EVENT_BUS.register(new VehicleEventHandler());

        // 注册结构生成系统（建筑 NBT + JSON 配置）
        SagadysseyStructures.STRUCTURE_TYPE_REGISTRY.register(modEventBus);
        SagadysseyStructures.PIECE_TYPE_REGISTRY.register(modEventBus);
        SagadysseyStructures.init();
        LOGGER.info("结构生成系统已加载（{} 个结构）", SagadysseyStructures.getAllIds().size());

        // 注册 GUI 事件
        modEventBus.addListener(this::onRegisterMenuScreens);

        // 初始化阵营数据（加载关系矩阵等非 Registry JSON）
        FactionDataLoader.init();
        LOGGER.info("阵营系统已初始化");

    }

    private void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.TEST_MENU.get(), TestScreen::new);
        event.register(ModMenuTypes.NPC_EQUIP.get(), NpcEquipScreen::new);
    }
}
