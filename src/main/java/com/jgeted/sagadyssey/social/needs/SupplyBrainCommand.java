package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** Tester-only trace and setup. Ordinary NPC/player projections do not receive ledger truth. */
@EventBusSubscriber(modid="sagadyssey")
public final class SupplyBrainCommand {
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("supply").requires(s->MinecraftApi.hasPermission(s, 2));
        root.then(Commands.literal("audit").executes(c->{var server=c.getSource().getServer();
            for(var r:NeedRequestData.get(server).requests())c.getSource().sendSuccess(()->Component.literal("需求 "+r.id()+" "+r.key().purpose()+" "+r.status()+" 原始/当前="+r.originalRequested()+"/"+r.requested()+" 预留/在途/交付/待补="+r.reserved()+"/"+r.inTransit()+"/"+r.delivered()+"/"+r.remaining()+" 原因="+r.detail()),false);
            for(var r:SupplyFlowData.get(server).routes())c.getSource().sendSuccess(()->Component.literal("运输单 "+r.id()+" →需求 "+r.need()+" "+r.stage()+" 携带者="+r.carrierCharacter()+" 原因="+r.detail()),false);
            for(var j:MerchantSupplyData.get(server).journeys())c.getSource().sendSuccess(()->Component.literal("商人路线 "+j.id()+" "+j.stage()+" 买入/卖出回执="+MerchantSupplyData.get(server).settlement(j.id(),true)+"/"+MerchantSupplyData.get(server).settlement(j.id(),false)+" 原因="+j.detail()),false);return 1;}));
        root.then(Commands.literal("cancel").then(Commands.argument("need",StringArgumentType.word()).executes(c->{var server=c.getSource().getServer();UUID id=UUID.fromString(StringArgumentType.getString(c,"need"));long now=server.overworld().getGameTime();var flows=SupplyFlowData.get(server);
            if(flows.routes().stream().anyMatch(r->r.need().equals(id)&&!r.closed()&&(r.stage()==SupplyFlowData.Stage.CARRYING||r.pending()!=null))){c.getSource().sendFailure(Component.literal("有在途或待核对货物，先完成交付/人工核对，不能清空库存义务"));return 0;}
            for(var r:flows.routes())if(r.need().equals(id)&&!r.closed())SupplyFlowService.release(server,r,now,"测试者取消需求");NeedRequestData.get(server).transition(id,NeedRequestStatus.CANCELLED,now,"测试者取消，预留已清理");return 1;})));
        root.then(Commands.literal("know-source").then(Commands.argument("npc",EntityArgument.entity()).then(Commands.argument("place",BlockPosArgument.blockPos()).executes(c->{if(!(EntityArgument.getEntity(c,"npc") instanceof NpcBase npc))return 0;
            var pos=BlockPosArgument.getLoadedBlockPos(c,"place");var server=c.getSource().getServer();String dim=c.getSource().getLevel().dimension().identifier().toString();
            UUID id=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(server,dim,pos);var owner=SubjectRef.character(npc.getCharacterId());var rights=RightsStateData.get(server);var context=rights.context(id);
            if(context==null)context=rights.ensureContext(id,dim+":"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);
            var point=new FieldworkStorage(dim,pos,id,context.defaultBeneficialOwner(),context.accessPolicy(),TransferIntent.DELIVERY,"explicit_supply_knowledge");
            SupplyFlowData.get(server).register(point);SupplyFlowData.get(server).reveal(owner,id);
            c.getSource().sendSuccess(()->Component.literal("测试者已告知储物地点；既有权属保留，未新增处分权"),false);return 1;}))));
        root.then(Commands.literal("household-source").then(Commands.argument("npc",EntityArgument.entity())
                .then(Commands.argument("place",BlockPosArgument.blockPos())
                        .executes(c->householdSource(c.getSource(),EntityArgument.getEntity(c,"npc"),BlockPosArgument.getLoadedBlockPos(c,"place"),false))
                        .then(Commands.literal("allow-transfer")
                                .executes(c->householdSource(c.getSource(),EntityArgument.getEntity(c,"npc"),BlockPosArgument.getLoadedBlockPos(c,"place"),true)))
                        .then(Commands.literal("register-empty")
                                .executes(c->householdStore(c.getSource(),EntityArgument.getEntity(c,"npc"),BlockPosArgument.getLoadedBlockPos(c,"place")))))));
        root.then(Commands.literal("household-buffer").then(Commands.argument("npc",EntityArgument.entity())
                .then(Commands.argument("category",StringArgumentType.word()).then(Commands.argument("item",net.minecraft.commands.arguments.IdentifierArgument.id())
                .then(Commands.argument("quantity",IntegerArgumentType.integer(1,1728)).then(Commands.argument("place",BlockPosArgument.blockPos())
                .executes(c->householdBuffer(c.getSource(),EntityArgument.getEntity(c,"npc"),StringArgumentType.getString(c,"category"),
                        net.minecraft.commands.arguments.IdentifierArgument.getId(c,"item").toString(),IntegerArgumentType.getInteger(c,"quantity"),BlockPosArgument.getLoadedBlockPos(c,"place")))))))));
        root.then(Commands.literal("buffer").then(Commands.argument("npc",EntityArgument.entity()).then(Commands.argument("category",StringArgumentType.word()).then(Commands.argument("item",net.minecraft.commands.arguments.IdentifierArgument.id()).then(Commands.argument("quantity",IntegerArgumentType.integer(1,1728)).then(Commands.argument("place",BlockPosArgument.blockPos()).executes(c->{if(!(EntityArgument.getEntity(c,"npc") instanceof NpcBase npc))return 0;
            var pos=BlockPosArgument.getLoadedBlockPos(c,"place");var server=c.getSource().getServer();String dim=c.getSource().getLevel().dimension().identifier().toString();var owner=SubjectRef.character(npc.getCharacterId());UUID id=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(server,dim,pos);
            var rights=RightsStateData.get(server);rights.ensureContext(id,dim+":"+pos.asLong(),owner,owner,AccessPolicy.PRIVATE);if(!rights.context(id).defaultBeneficialOwner().equals(owner))throw new IllegalStateException("目标储物不归此 NPC");
            var point=new FieldworkStorage(dim,pos,id,owner,rights.context(id).accessPolicy(),TransferIntent.DELIVERY,"explicit_buffer");var flows=SupplyFlowData.get(server);flows.register(point);
            String category=StringArgumentType.getString(c,"category"),item=net.minecraft.commands.arguments.IdentifierArgument.getId(c,"item").toString();int quantity=IntegerArgumentType.getInteger(c,"quantity");
            flows.policy(new SupplyFlowData.Policy("tester:"+npc.getCharacterId()+":"+id+":"+item,owner,id,new ItemFingerprint(item,0),quantity,category));c.getSource().sendSuccess(()->Component.literal("真实储备目标已登记；不会生成物品或更改NPC模式"),false);return 1;})))))));
        root.then(Commands.literal("quote").then(Commands.argument("direction",StringArgumentType.word())
                .then(Commands.argument("item",StringArgumentType.word()).then(Commands.argument("price",IntegerArgumentType.integer(1,64))
                .then(Commands.argument("quantity",IntegerArgumentType.integer(1,64)).then(Commands.argument("settlement",StringArgumentType.word())
                .then(Commands.argument("place",BlockPosArgument.blockPos()).executes(c->{
                    var server=c.getSource().getServer();var pos=BlockPosArgument.getLoadedBlockPos(c,"place");String dim=c.getSource().getLevel().dimension().identifier().toString();
                    UUID pointId=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(server,dim,pos);var context=RightsStateData.get(server).context(pointId);
                    if(context==null){c.getSource().sendFailure(Component.literal("请先登记实体储物的权属，报价不能创建所有权"));return 0;}
                    var point=new FieldworkStorage(dim,pos,pointId,context.defaultBeneficialOwner(),context.accessPolicy(),TransferIntent.PAYMENT,"tester_owner_quote");
                    if(SupplyFlowService.container(server,point)==null){c.getSource().sendFailure(Component.literal("报价必须绑定真实储物容器"));return 0;}
                    String direction=StringArgumentType.getString(c,"direction");if(!Set.of("sell","buy").contains(direction))return 0;
                    var quote=new MerchantSupplyData.Offer(UUID.randomUUID(),pointId,UUID.fromString(StringArgumentType.getString(c,"settlement")),
                            new ItemFingerprint(StringArgumentType.getString(c,"item"),0),direction.equals("sell"),IntegerArgumentType.getInteger(c,"price"),IntegerArgumentType.getInteger(c,"quantity"),server.overworld().getGameTime()+24000);
                    SupplyFlowData.get(server).register(point);MerchantSupplyData.get(server).offer(server,point.owner(),quote);
                    c.getSource().sendSuccess(()->Component.literal("测试者代持有者声明真实报价 "+quote.id()+"；未生成物品或绿宝石，也未告知任何商人"),false);return 1;
                }))))))));
        root.then(Commands.literal("learn-quote").then(Commands.argument("npc",EntityArgument.entity())
                .then(Commands.argument("quote",StringArgumentType.word()).then(Commands.argument("observedQuantity",IntegerArgumentType.integer(0,64)).executes(c->{
                    if(!(EntityArgument.getEntity(c,"npc") instanceof NpcBase npc))return 0;
                    var quote=MerchantSupplyData.get(c.getSource().getServer()).offer(UUID.fromString(StringArgumentType.getString(c,"quote")));
                    int quantity=IntegerArgumentType.getInteger(c,"observedQuantity");if(quantity>quote.limit())return 0;
                    MerchantSupplyData.get(MinecraftApi.server(npc)).reveal(npc.getCharacterId(),new MerchantSupplyData.Observation(quote.id(),quantity,c.getSource().getLevel().getGameTime()));
                    c.getSource().sendSuccess(()->Component.literal("已向此商人显式传达报价观察；抵达后仍须核对真实现货、付款和权限"),false);return 1;
                })))));
        event.getDispatcher().register(Commands.literal("saga").then(root));
    }
    private static int householdStore(net.minecraft.commands.CommandSourceStack source,net.minecraft.world.entity.Entity entity,net.minecraft.core.BlockPos position) {
        if(!(entity instanceof NpcBase npc))return 0;
        var server=source.getServer();String dimension=source.getLevel().dimension().identifier().toString();
        var home=com.jgeted.sagadyssey.social.household.HouseholdData.get(server).forMember(npc.getCharacterId());
        var inventory=com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(source.getLevel().getBlockEntity(position));
        if(home==null||!home.dimension().equals(dimension)||!npc.level().dimension().identifier().toString().equals(dimension)||inventory==null){source.sendFailure(Component.literal("需要同维度家庭和真实储物容器"));return 0;}
        if(com.jgeted.sagadyssey.social.household.HouseholdData.get(server).households().stream().anyMatch(h->!h.id().equals(home.id())&&h.dimension().equals(dimension)&&position.equals(h.sharedStorage()))) {
            source.sendFailure(Component.literal("不能登记另一个家庭已经分配的储物"));return 0;
        }
        UUID id=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(server,dimension,position);
        var rights=RightsStateData.get(server);var principal=SubjectRef.household(home.id());var context=rights.context(id);
        if(context==null){
            if(!inventory.isEmpty()||com.jgeted.sagadyssey.social.life.NpcLifeData.get(server).privateStorageAssigned(position,dimension)) {
                source.sendFailure(Component.literal("只能明确登记未占用的空置储物，不能接管私人或已有物品"));return 0;
            }
            rights.ensureContext(id,dimension+":"+position.asLong(),principal,principal,AccessPolicy.HOUSEHOLD);
        }else if(!context.defaultBeneficialOwner().equals(principal)) {source.sendFailure(Component.literal("不能变更已有来源所有权"));return 0;}
        return householdSource(source,entity,position,false);
    }
    private static int householdBuffer(net.minecraft.commands.CommandSourceStack source,net.minecraft.world.entity.Entity entity,
                                       String category,String item,int quantity,net.minecraft.core.BlockPos position) {
        if(!Set.of("household","workshop","institution").contains(category)){source.sendFailure(Component.literal("未知储备类别"));return 0;}
        if(householdStore(source,entity,position)!=1)return 0;
        var npc=(NpcBase)entity;var server=source.getServer();String dimension=source.getLevel().dimension().identifier().toString();
        var home=com.jgeted.sagadyssey.social.household.HouseholdData.get(server).forMember(npc.getCharacterId());
        UUID id=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(server,dimension,position);
        SupplyFlowData.get(server).policy(new SupplyFlowData.Policy("household:"+home.id()+":"+id+":"+item,SubjectRef.household(home.id()),id,new ItemFingerprint(item,0),quantity,category));
        source.sendSuccess(()->Component.literal("已登记家庭实体储备，由原运输机制履行；未生成物品"),false);return 1;
    }
    /** Explicit administration: knowledge and disposition authority remain distinct operations. */
    private static int householdSource(net.minecraft.commands.CommandSourceStack source,net.minecraft.world.entity.Entity entity,
                                       net.minecraft.core.BlockPos position,boolean allowTransfer) {
        if(!(entity instanceof NpcBase npc))return 0;
        var server=source.getServer();String dimension=source.getLevel().dimension().identifier().toString();
        var home=com.jgeted.sagadyssey.social.household.HouseholdData.get(server).forMember(npc.getCharacterId());
        if(home==null||!home.dimension().equals(dimension)||!npc.level().dimension().identifier().toString().equals(dimension)) {
            source.sendFailure(Component.literal("此成员没有同维度家庭，不能替其他家庭登记来源"));return 0;
        }
        if(com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(source.getLevel().getBlockEntity(position))==null) {
            source.sendFailure(Component.literal("来源必须是已加载的受支持储物容器"));return 0;
        }
        UUID id=com.jgeted.sagadyssey.social.production.ProductionContainerIds.resolve(server,dimension,position);
        var rights=RightsStateData.get(server);var context=rights.context(id);
        if(context==null){source.sendFailure(Component.literal("请先明确登记来源权属；告知地点不会创建所有权"));return 0;}
        var principal=SubjectRef.household(home.id());var owner=SubjectRef.character(npc.getCharacterId());
        if(allowTransfer&&!context.defaultBeneficialOwner().equals(owner)&&!context.defaultBeneficialOwner().equals(principal)) {
            source.sendFailure(Component.literal("只能由此成员或家庭自己的来源明确授权；其他来源仅可登记知识"));return 0;
        }
        if(allowTransfer&&context.defaultBeneficialOwner().equals(owner)) {
            var inventory=com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(source.getLevel().getBlockEntity(position));
            for(int slot=0;slot<inventory.getContainerSize();slot++) {
                var stack=inventory.getItem(slot);if(stack.isEmpty())continue;
                if(rights.claimsAt(id,slot,ItemFingerprint.of(stack)).stream().anyMatch(claim->!claim.beneficialOwner().equals(owner))) {
                    source.sendFailure(Component.literal("来源内存在他人物权主张，不能代为授权"));return 0;
                }
            }
        }
        var flows=SupplyFlowData.get(server);
        flows.register(new FieldworkStorage(dimension,position,id,context.defaultBeneficialOwner(),context.accessPolicy(),TransferIntent.DELIVERY,"explicit_household_supply_knowledge"));
        flows.reveal(principal,id);
        if(allowTransfer&&!context.defaultBeneficialOwner().equals(principal)) {
            long now=server.overworld().getGameTime();
            rights.grant(new RightGrant(ContainerIds.named("household-supply-transfer:"+home.id()+":"+id),id,principal,
                    Set.of(AssetRight.TRANSFER),"管理员明确配置成员来源向其家庭转移物资",now,null,true));
        }
        source.sendSuccess(()->Component.literal(allowTransfer?"已登记家庭来源并明确授权转移；来源所有权保持不变":"已告知家庭来源；未新增转移权"),false);
        return 1;
    }

}
