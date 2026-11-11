package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.knowledge.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** Public self-service is knower-scoped. Only permission-2 setup/audit exposes backstage truth. */
@EventBusSubscriber(modid="sagadyssey")
public final class ReligionCommand{
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("religion");
        root.then(Commands.literal("here").executes(c->safe(c,()->{
            var p=c.getSource().getPlayerOrException();var nearby=ReligionData.get(p.level().getServer()).temples().stream().filter(t->ReligionService.near(p,t)).toList();
            if(nearby.isEmpty()){c.getSource().sendSuccess(()->Component.literal("你附近没有可确认的教堂接待窗口。"),false);return 0;}
            for(var t:nearby){String prefix="/saga religion ";c.getSource().sendSuccess(()->Component.literal(t.name()+"：此处接待自愿捐赠与食物救济申请，需当面审核。"),false);
                c.getSource().sendSuccess(()->Component.literal("[捐赠面包（济贫）]").withStyle(s->s.withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand(prefix+"donate "+t.id()+" minecraft:bread 1 ALMS "+UUID.randomUUID()))),false);
                c.getSource().sendSuccess(()->Component.literal("[申请食物救济]").withStyle(s->s.withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand(prefix+"ask "+t.id()+" "+Math.min(3,t.portion())+" 我需要一份食物"))),false);
                c.getSource().sendSuccess(()->Component.literal("[查看自己已知记录]").withStyle(s->s.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(prefix+"records "+t.id()))),false);
            }return nearby.size();})));
        root.then(Commands.literal("visit").then(Commands.argument("temple",StringArgumentType.word()).executes(c->safe(c,()->{
            var p=c.getSource().getPlayerOrException();var t=temple(c);if(!ReligionService.near(p,t))throw new IllegalStateException("请先到这座教堂的接待窗口");long now=p.level().getGameTime();
            var observer=ReligionService.currentCharacter(p);if(observer==null){c.getSource().sendFailure(Component.literal("当前没有可用的在生人物；查询不会创建人物。"));return 0;}
            KnowledgeStateData.get(p.level().getServer()).observe(observer,new FactKey(t.id(),"religion.discovered",""),t.name()+"：公开食品救济，单次上限 "+t.portion()+"；审核和库存许可后领取",PrecisionBand.CATEGORY,1,"教堂现场公布",now,0,DisclosurePolicy.PUBLIC);
            c.getSource().sendSuccess(()->Component.literal("此处是 "+t.name()+"。可捐赠、申请救济或查看自己已知记录；不公开内部库存。"),false);return 1;}))));
        var gift=Commands.argument("purpose",StringArgumentType.word()).executes(c->safe(c,()->donate(c,UUID.randomUUID())));
        gift.then(Commands.argument("consent",StringArgumentType.word()).executes(c->safe(c,()->donate(c,UUID.fromString(StringArgumentType.getString(c,"consent"))))));
        root.then(Commands.literal("donate").then(Commands.argument("temple",StringArgumentType.word()).then(Commands.argument("item",net.minecraft.commands.arguments.IdentifierArgument.id()).then(Commands.argument("quantity",IntegerArgumentType.integer(1,1728)).then(gift)))));
        root.then(Commands.literal("ask").then(Commands.argument("temple",StringArgumentType.word()).then(Commands.argument("quantity",IntegerArgumentType.integer(1,64)).then(Commands.argument("statement",StringArgumentType.greedyString()).executes(c->safe(c,()->{
            var applicant=c.getSource().getPlayerOrException();var value=ReligionService.request(applicant.level().getServer(),temple(c),applicant,new ItemFingerprint("minecraft:bread",0),IntegerArgumentType.getInteger(c,"quantity"),StringArgumentType.getString(c,"statement"));
            c.getSource().sendSuccess(()->Component.literal("自己的申请已登记，等待当面审查；同一教堂、人物和时间窗内不会重复领粮。"),false);return 1;}))))));
        root.then(Commands.literal("records").then(Commands.argument("temple",StringArgumentType.word()).executes(c->safe(c,()->{
            var p=c.getSource().getPlayerOrException();var observer=ReligionService.currentCharacter(p);if(observer==null){c.getSource().sendFailure(Component.literal("当前没有可用的在生人物；查询不会创建人物。"));return 0;}var records=ReligionService.knownRecords(p.level().getServer(),observer,UUID.fromString(StringArgumentType.getString(c,"temple")));
            if(records.isEmpty())c.getSource().sendSuccess(()->Component.literal("你尚无可确认的记录。"),false);else for(String value:records)c.getSource().sendSuccess(()->Component.literal(value),false);return records.size();}))));
        root.then(Commands.literal("buildings").requires(s->MinecraftApi.hasPermission(s, 2)).executes(c->safe(c,()->{
            for(var b:SettlementRegistryData.get(c.getSource().getServer()).buildings())if(b.category().equals("church"))c.getSource().sendSuccess(()->Component.literal("教堂建筑 "+b.key()+" "+b.dimension()+" "+b.position()),false);return 1;})));
        var bind=Commands.argument("portion",IntegerArgumentType.integer(1,64)).executes(c->safe(c,()->{
            var server=c.getSource().getServer();var founder=c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p?SubjectRef.character(ReligionService.character(p)):SubjectRef.character(ContainerIds.named("temple-test-admin"));
            var t=ChapelBootstrapService.bind(server,StringArgumentType.getString(c,"building"),BlockPosArgument.getLoadedBlockPos(c,"treasury"),BlockPosArgument.getLoadedBlockPos(c,"alms"),BlockPosArgument.getLoadedBlockPos(c,"counter"),founder,IntegerArgumentType.getInteger(c,"retained"),IntegerArgumentType.getInteger(c,"portion"));
            c.getSource().sendSuccess(()->Component.literal("本地 TEMPLE 已绑定 "+t.id()+"；未创建物品、人物或共同阵营库存"),false);return 1;}));
        root.then(Commands.literal("bind").requires(s->MinecraftApi.hasPermission(s, 2)).then(Commands.argument("building",StringArgumentType.string()).then(Commands.argument("treasury",BlockPosArgument.blockPos()).then(Commands.argument("alms",BlockPosArgument.blockPos()).then(Commands.argument("counter",BlockPosArgument.blockPos()).then(Commands.argument("retained",IntegerArgumentType.integer(0,1728)).then(bind)))))));
        root.then(Commands.literal("appoint").requires(s->MinecraftApi.hasPermission(s, 2)).then(Commands.argument("temple",StringArgumentType.word()).then(Commands.argument("npc",EntityArgument.entity()).then(Commands.argument("role",StringArgumentType.word()).executes(c->safe(c,()->{
            if(!(EntityArgument.getEntity(c,"npc") instanceof NpcBase n))throw new IllegalArgumentException("职位须绑定真实 NPC");var t=temple(c);
            var role=TempleRecord.Role.valueOf(StringArgumentType.getString(c,"role").toUpperCase(Locale.ROOT));var office=ChapelBootstrapService.appoint(MinecraftApi.server(n),t,SubjectRef.institution(t.id()),n,role);
            c.getSource().sendSuccess(()->Component.literal("测试者代机构登记狭义职位 "+office.id()+" "+office.title()+"；未改变人物职业、私产、家庭或行为模式"),false);return 1;}))))));
        root.then(Commands.literal("revoke").requires(s->MinecraftApi.hasPermission(s, 2)).then(Commands.argument("office",StringArgumentType.word()).executes(c->safe(c,()->InstitutionData.get(c.getSource().getServer()).revokeOffice(UUID.fromString(StringArgumentType.getString(c,"office")))?1:0))));
        root.then(Commands.literal("know-supply").requires(s->MinecraftApi.hasPermission(s, 2)).then(Commands.argument("temple",StringArgumentType.word()).then(Commands.argument("place",BlockPosArgument.blockPos()).executes(c->safe(c,()->{
            var t=temple(c);var pos=BlockPosArgument.getLoadedBlockPos(c,"place");var server=c.getSource().getServer();String dim=c.getSource().getLevel().dimension().identifier().toString();UUID id=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(server,dim,pos);var context=RightsStateData.get(server).context(id);
            if(context==null||!context.defaultBeneficialOwner().equals(SubjectRef.institution(t.id())))throw new IllegalStateException("供给地点必须已依法归此本地机构所有；告知不能替代产权转移");
            var point=new com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage(dim,pos,id,context.defaultBeneficialOwner(),context.accessPolicy(),TransferIntent.DELIVERY,"known_temple_supply");if(SupplyFlowService.container(server,point)==null)throw new IllegalStateException("实际储物容器不可用");SupplyFlowData.get(server).register(point);SupplyFlowData.get(server).reveal(SubjectRef.institution(t.id()),id);return 1;})))));
        root.then(Commands.literal("audit").requires(s->MinecraftApi.hasPermission(s, 2)).executes(c->safe(c,()->audit(c))));
        event.getDispatcher().register(Commands.literal("saga").then(root));
    }
    private static TempleRecord temple(CommandContext<CommandSourceStack> c){return ReligionData.get(c.getSource().getServer()).temple(UUID.fromString(StringArgumentType.getString(c,"temple")));}
    private static int donate(CommandContext<CommandSourceStack> c,UUID id)throws CommandSyntaxException{
        var p=c.getSource().getPlayerOrException();var gift=ReligionService.donate(p.level().getServer(),id,p,temple(c),new ItemFingerprint(net.minecraft.commands.arguments.IdentifierArgument.getId(c,"item").toString(),0),IntegerArgumentType.getInteger(c,"quantity"),DonationRecord.Purpose.valueOf(StringArgumentType.getString(c,"purpose").toUpperCase(Locale.ROOT)));
        c.getSource().sendSuccess(()->Component.literal("自己的捐赠 "+gift.status()+"，意图/收据编号 "+gift.id()+"；"+gift.detail()),false);return gift.status()==DonationRecord.Status.RECORDED?1:0;
    }
    private static int audit(CommandContext<CommandSourceStack> c){var server=c.getSource().getServer();var d=ReligionData.get(server);long now=server.overworld().getGameTime();
        for(var t:d.temples())c.getSource().sendSuccess(()->Component.literal("TEMPLE "+t.id()+" building="+t.building()+" 财库="+t.treasury().position()+" 济贫仓="+t.alms().position()+" bread="+SupplyFlowService.count(SupplyFlowService.container(server,t.alms()),new ItemFingerprint("minecraft:bread",0))+" 保留="+t.retained()+" 产权/设施有效="+SupplyFlowService.validOwner(server,t.alms())+"/"+(SupplyFlowService.container(server,t.alms())!=null)),false);
        for(var office:InstitutionData.get(server).offices())if(d.temples().stream().anyMatch(t->t.id().equals(office.institutionId())))c.getSource().sendSuccess(()->Component.literal("职位 "+office.id()+" "+office.title()+" holder="+office.holder()+" powers="+office.authorities()+" active="+office.activeAt(now)),false);
        for(var a:d.cases())c.getSource().sendSuccess(()->Component.literal("救济 "+a.id()+" temple="+a.temple()+" recipient="+a.recipient()+" "+a.stage()+" qty="+a.quantity()+" reviewer="+a.reviewer()+" worker="+a.workerCharacter()+" pickup/gift="+d.receipt(a.id(),true)+"/"+d.receipt(a.id(),false)+" lease="+RightsStateData.get(server).reservationsForToken(a.token(),now).stream().mapToInt(AssetReservation::quantity).sum()+" retry="+a.retryAt()+" reason="+a.detail()),false);
        for(var gift:d.donations()){var receipt=TransferLedgerData.get(server).receipt(gift.id());c.getSource().sendSuccess(()->Component.literal("捐赠 "+gift.id()+" temple="+gift.temple()+" "+gift.purpose()+" "+gift.status()+" qty="+gift.quantity()+" receipt="+(receipt==null?"MISSING":receipt.status())),false);}return 1;
    }
    @FunctionalInterface private interface Action{int run()throws CommandSyntaxException;}
    private static int safe(CommandContext<CommandSourceStack> c,Action a)throws CommandSyntaxException{try{return a.run();}catch(RuntimeException e){c.getSource().sendFailure(Component.literal("教堂操作未完成："+e.getMessage()));return 0;}}
}
