package com.jgeted.sagadyssey.social.politics;

import com.jgeted.sagadyssey.npc.identity.NpcOffice;
import com.jgeted.sagadyssey.npc.identity.NpcSocialClass;
import com.jgeted.sagadyssey.npc.identity.PlayerIdentityAttachments;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.jgeted.sagadyssey.social.settlement.SettlementRecord;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;

import java.util.UUID;

/** 政治状态的查询与管理员任命入口。 */
public final class PoliticalCommand {
    private PoliticalCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerConflict(dispatcher);
        registerReserve(dispatcher);
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("politics")
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("relation")
                                .then(Commands.argument("source", StringArgumentType.word())
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .executes(ctx -> relation(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "source"),
                                                        StringArgumentType.getString(ctx, "target"))))))
                        .then(Commands.literal("set_relation")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("source", StringArgumentType.word())
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .then(Commands.argument("relation", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (PoliticalRelation relation : PoliticalRelation.values()) {
                                                                builder.suggest(relation.name().toLowerCase(java.util.Locale.ROOT));
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> setRelation(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "source"),
                                                                StringArgumentType.getString(ctx, "target"),
                                                                StringArgumentType.getString(ctx, "relation")))))))
                        .then(Commands.literal("appoint")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("faction", StringArgumentType.word())
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> appoint(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "faction"),
                                                        EntityArgument.getPlayer(ctx, "player"))))))));

        dispatcher.register(Commands.literal("saga").then(Commands.literal("politics")
                .then(Commands.literal("realm")
                        .executes(ctx -> realm(ctx.getSource())))
                .then(Commands.literal("swear")
                        .then(Commands.argument("liege", EntityArgument.player())
                                .executes(ctx -> swear(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "liege")))))
                .then(Commands.literal("heir")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> heir(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("grant")
                        .then(Commands.argument("settlement_id", StringArgumentType.word())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> grant(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "settlement_id"),
                                                EntityArgument.getPlayer(ctx, "player"))))))));
    }

    private static void registerConflict(CommandDispatcher<CommandSourceStack> dispatcher) {
        for(String action:java.util.List.of("prepare","declare","peace-propose","peace-accept","withdraw","cancel"))
            dispatcher.register(Commands.literal("saga").then(Commands.literal("politics").then(Commands.literal("conflict")
                .then(Commands.literal(action).requires(source -> source.getEntity() instanceof com.jgeted.sagadyssey.npc.entity.NpcBase || source.getEntity() instanceof ServerPlayer)
                    .then(Commands.argument("self",net.minecraft.commands.arguments.UuidArgument.uuid())
                        .then(Commands.argument("peer",net.minecraft.commands.arguments.UuidArgument.uuid())
                            .then(Commands.argument("deal",net.minecraft.commands.arguments.UuidArgument.uuid()).executes(c -> {
                                var source=c.getSource();var result=LimitedConflictGovernance.action(source.getServer(),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"self"),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"peer"),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"deal"),CharterTradeGovernance.actor(source.getEntity()),action);
                                if(result.accepted())source.sendSuccess(() -> Component.literal(result.reason()),false);else source.sendFailure(Component.literal(result.reason()));return result.accepted()?1:0;
                            }))))))));
    }

    private static void registerReserve(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        for(String action: java.util.List.of("enable","donate","relief","status"))
            dispatcher.register(Commands.literal("saga").then(Commands.literal("politics").then(Commands.literal("reserve")
                .then(Commands.literal(action).requires(source -> source.getEntity() instanceof com.jgeted.sagadyssey.npc.entity.NpcBase || source.getEntity() instanceof ServerPlayer)
                    .then(Commands.argument("self",net.minecraft.commands.arguments.UuidArgument.uuid()).executes(c -> reserveAction(c.getSource(),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"self"),null,action)))))));
        for(String action: java.util.List.of("propose","approve","pause","status"))
            dispatcher.register(Commands.literal("saga").then(Commands.literal("politics").then(Commands.literal("pact")
                .then(Commands.literal(action).requires(source -> source.getEntity() instanceof com.jgeted.sagadyssey.npc.entity.NpcBase || source.getEntity() instanceof ServerPlayer)
                    .then(Commands.argument("self",net.minecraft.commands.arguments.UuidArgument.uuid())
                        .then(Commands.argument("peer",net.minecraft.commands.arguments.UuidArgument.uuid()).executes(c -> reserveAction(c.getSource(),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"self"),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"peer"),action))))))));
    }
    private static int reserveAction(CommandSourceStack source,UUID self,UUID peer,String action) {
        var server=source.getServer();UUID actor=CharterTradeGovernance.actor(source.getEntity());var d=NaturalPolityData.get(server);
        CharterTradeGovernance.Outcome result;
        if(peer!=null){
            if(action.equals("status")){var t=CharterTradeGovernance.pact(server,self,peer);result=new CharterTradeGovernance.Outcome(true,t==null?"NONE":t.getString("State"),t==null?"尚无本双方约定":t.getString("Reason"));}
            else result=CharterTradeGovernance.agreement(server,self,peer,actor,action);
        } else if(action.equals("enable"))result=CharterTradeGovernance.enable(server,self,actor);
        else if(action.equals("donate"))result=CharterTradeGovernance.donate(server,self,source.getEntity());
        else if(action.equals("status")){var row=d.site(self);result=new CharterTradeGovernance.Outcome(row!=null,row==null?"NONE":row.getCompound("ReserveRule").getString("State"),row==null?"未知聚落":row.getCompound("ReserveRule").getString("Reason"));}
        else {
            var row=d.site(self);var home=actor==null?null:com.jgeted.sagadyssey.social.household.HouseholdData.get(server).forMember(actor);
            boolean hungry=source.getEntity() instanceof ServerPlayer player?player.getFoodData().getFoodLevel()<=10:actor!=null&&com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).needs(actor)!=null&&com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).needs(actor).hunger()>=350;
            if(row==null||home==null||!self.equals(home.settlementId())||!hungry)result=new CharterTradeGovernance.Outcome(false,"DENIED","本户真实饥饿成员才可请求有限公共口粮");
            else {String state=d.relief(server,NaturalPolityData.building(row),actor,home.id());result=new CharterTradeGovernance.Outcome(state.equals("DONE"),state,state.equals("DONE")?"原公职决定已交付两份实际公共口粮":state);}
        }
        var answer=result;if(answer.accepted())source.sendSuccess(() -> Component.literal(answer.state()+"："+answer.reason()),false);else source.sendFailure(Component.literal(answer.reason()));return answer.accepted()?1:0;
    }

    private static int realm(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var character = CharacterRegistry.current(player);
        if (character == null) {
            source.sendFailure(Component.literal("当前没有已绑定人物；查询不会创建人物。"));
            return 0;
        }
        PoliticalStateData politics = PoliticalStateData.get(source.getServer());
        var liegeId = politics.liegeOf(character.id());
        var heirId = politics.designatedHeir(character.id());
        String liege = characterName(source, liegeId, "无");
        String heir = characterName(source, heirId, "未指定");
        source.sendSuccess(() -> Component.literal("§6" + character.name() + " 的封建关系：§7封君="
                + liege + "，封臣=" + politics.vassalCount(character.id()) + "，继承人=" + heir), false);
        for (UUID settlementId : politics.titlesOf(character.id())) {
            SettlementRecord settlement = SettlementRegistryData.get(source.getServer()).byId(settlementId);
            source.sendSuccess(() -> Component.literal("  §e领地：§f"
                    + (settlement == null ? settlementId : settlement.name() + " [" + settlement.factionId() + "]")), false);
        }
        return 1;
    }

    private static int swear(CommandSourceStack source, ServerPlayer liegePlayer)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var vassal = CharacterRegistry.ensureCurrent(player);
        var liege = CharacterRegistry.ensureCurrent(liegePlayer);
        if (!vassal.factionId().equals(liege.factionId())) {
            source.sendFailure(Component.literal("只能向同一阵营的人物宣誓效忠。"));
            return 0;
        }
        if (!PoliticalStateData.get(source.getServer()).swearFealty(source.getServer(),
                vassal.id(), vassal.name(), liege.id(), liege.name(), source.getLevel().getGameTime())) {
            source.sendFailure(Component.literal("无法效忠：不能效忠自己或形成封臣循环。"));
            return 0;
        }
        var identity = PlayerIdentityAttachments.get(player);
        identity.set(identity.socialClass(), NpcOffice.SWORN, liege.factionId());
        CharacterRegistry.updateIdentity(player, identity.socialClass(), NpcOffice.SWORN, liege.factionId());
        source.sendSuccess(() -> Component.literal("§a" + vassal.name() + " 已向 " + liege.name() + " 宣誓效忠。"), true);
        return 1;
    }

    private static int heir(CommandSourceStack source, ServerPlayer heirPlayer)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var holder = CharacterRegistry.ensureCurrent(player);
        var heir = CharacterRegistry.ensureCurrent(heirPlayer);
        if (!holder.factionId().equals(heir.factionId())) {
            source.sendFailure(Component.literal("继承人必须属于同一阵营。"));
            return 0;
        }
        if (!PoliticalStateData.get(source.getServer()).designateHeir(source.getServer(),
                holder.id(), holder.name(), heir.id(), heir.name(), source.getLevel().getGameTime())) {
            source.sendFailure(Component.literal("只有实际持有王位或聚落头衔的人物才能指定继承人。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§a已指定 " + heir.name() + " 为继承人。"), true);
        return 1;
    }

    private static int grant(CommandSourceStack source, String settlementId, ServerPlayer target) {
        UUID id;
        try { id = UUID.fromString(settlementId); }
        catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("聚落 ID 格式无效。"));
            return 0;
        }
        SettlementRecord settlement = SettlementRegistryData.get(source.getServer()).byId(id);
        if (settlement == null) {
            source.sendFailure(Component.literal("找不到该聚落。"));
            return 0;
        }
        PoliticalStateData politics = PoliticalStateData.get(source.getServer());
        if (!source.hasPermission(2)) {
            if (!(source.getEntity() instanceof ServerPlayer granter)) {
                source.sendFailure(Component.literal("只有王权持有者或当前领主可以授予领地。"));
                return 0;
            }
            var character = CharacterRegistry.ensureCurrent(granter);
            if (!politics.canGrantTitle(settlement.factionId(), settlement.id(), character.id())) {
                source.sendFailure(Component.literal("你没有该聚落的授封权。"));
                return 0;
            }
        }
        var targetCharacter = CharacterRegistry.ensureCurrent(target);
        politics.grantTitle(source.getServer(), settlement.id(), settlement.name(), settlement.factionId(),
                targetCharacter.id(), targetCharacter.name(), source.getLevel().getGameTime());
        var identity = PlayerIdentityAttachments.get(target);
        NpcSocialClass socialClass = identity.socialClass().precedence() < NpcSocialClass.NOBLE.precedence()
                ? NpcSocialClass.NOBLE : identity.socialClass();
        identity.set(socialClass, NpcOffice.LOCAL_RULER, settlement.factionId());
        CharacterRegistry.updateIdentity(target, socialClass, NpcOffice.LOCAL_RULER, settlement.factionId());
        source.sendSuccess(() -> Component.literal("§a已将 " + settlement.name() + " 授予 "
                + targetCharacter.name() + "。"), true);
        return 1;
    }

    private static String characterName(CommandSourceStack source, UUID id, String fallback) {
        if (id == null) return fallback;
        var record = CharacterRegistryData.get(source.getServer()).byId(id);
        return record == null ? id.toString() : record.name();
    }

    private static int status(CommandSourceStack source) {
        PoliticalStateData data = PoliticalStateData.get(source.getServer());
        source.sendSuccess(() -> Component.literal("§6世界政治状态："), false);
        for (String faction : data.knownFactions().stream().sorted().toList()) {
            var rulerId = data.ruler(faction);
            String rulerName = "制度性统治者";
            if (data.isOfficeVacant(faction)) {
                rulerName = "空缺";
            } else if (rulerId != null) {
                var record = CharacterRegistryData.get(source.getServer()).byId(rulerId);
                rulerName = record == null ? rulerId.toString() : record.name();
            }
            String finalRulerName = rulerName;
            source.sendSuccess(() -> Component.literal("§e" + faction + " §7合法性="
                    + data.legitimacy(faction) + "，统治者=" + finalRulerName), false);
        }
        return data.knownFactions().size();
    }

    private static int relation(CommandSourceStack source, String from, String to) {
        PoliticalRelation relation = PoliticalStateData.get(source.getServer()).relation(from, to);
        source.sendSuccess(() -> Component.literal("§e" + from + " §7→ §e" + to
                + "§7：§f" + relation.name()), false);
        return 1;
    }

    private static int setRelation(CommandSourceStack source, String from, String to, String relationName) {
        PoliticalRelation relation;
        try {
            relation = PoliticalRelation.valueOf(relationName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("无效政治关系：" + relationName));
            return 0;
        }
        PoliticalStateData.get(source.getServer()).setRelation(source.getServer(), from, to,
                relation, source.getLevel().getGameTime());
        source.sendSuccess(() -> Component.literal("§a政治关系已更新：" + from + " → " + to
                + " = " + relation.name()), true);
        return 1;
    }

    private static int appoint(CommandSourceStack source, String faction, ServerPlayer player) {
        var character = CharacterRegistry.ensureCurrent(player);
        var identity = PlayerIdentityAttachments.get(player);
        NpcSocialClass socialClass = identity.socialClass().precedence() < NpcSocialClass.ROYAL.precedence()
                ? NpcSocialClass.ROYAL : identity.socialClass();
        identity.set(socialClass, NpcOffice.SOVEREIGN, faction);
        CharacterRegistry.updateIdentity(player, socialClass, NpcOffice.SOVEREIGN, faction);
        PoliticalStateData.get(source.getServer()).appointRuler(source.getServer(), faction,
                character.id(), character.name(), source.getLevel().getGameTime());
        source.sendSuccess(() -> Component.literal("§a已任命 " + character.name() + " 为 "
                + faction + " 的最高统治者。"), true);
        return 1;
    }
}
