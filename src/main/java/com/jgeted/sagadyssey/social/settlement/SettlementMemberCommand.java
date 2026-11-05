package com.jgeted.sagadyssey.social.settlement;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.household.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import java.util.*;

/** Ordinary nearby membership and one concrete paid delivery per character/game day. */
public final class SettlementMemberCommand {
    private SettlementMemberCommand(){}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("saga").then(Commands.literal("society")
                .then(Commands.literal("nearby").executes(c->nearby(c.getSource().getPlayerOrException())))
                .then(Commands.literal("join").executes(c->join(c.getSource().getPlayerOrException())?1:0))
                .then(Commands.literal("leave").executes(c->leave(c.getSource().getPlayerOrException())?1:0))
                .then(Commands.literal("status").executes(c->status(c.getSource().getPlayerOrException())))
                .then(Commands.literal("institutions").executes(c->institutions(c.getSource().getPlayerOrException())))
                .then(Commands.literal("relief").executes(c->relief(c.getSource().getPlayerOrException())))
                .then(Commands.literal("peace").executes(c->peace(c.getSource().getPlayerOrException())))
                .then(Commands.literal("work").executes(c->work(c.getSource().getPlayerOrException())?1:0))
                .then(Commands.literal("cancel-work").executes(c->cancelWork(c.getSource().getPlayerOrException())?1:0))
                .then(Commands.literal("deliver").executes(c->deliver(c.getSource().getPlayerOrException())?1:0))));
    }
    public static NaturalVillageBuilding nearest(ServerPlayer p){
        var d=NaturalVillageLifeData.existing(p.server);if(d==null)return null;
        return d.villages().stream().filter(v->v.getBoolean("Ready")).map(NaturalVillageLifeData::decode)
                .filter(b->b.dimension().equals(p.level().dimension().location().toString())&&p.blockPosition().distSqr(b.buildingOrigin())<=64*64)
                .min(Comparator.comparingDouble(b->p.blockPosition().distSqr(b.buildingOrigin()))).orElse(null);
    }
    private static void message(ServerPlayer p,String text){p.sendSystemMessage(Component.literal(text));}
    private static int nearby(ServerPlayer p){var b=nearest(p);message(p,b==null?"附近没有已建立生活条件的聚落":"附近有村庄，可用 /saga society join 申请普通成员身份");return b==null?0:1;}
    public static boolean join(ServerPlayer p){var b=nearest(p);if(b==null){message(p,"附近没有已建立生活条件的聚落；使用 /saga society nearby 查找");return false;}var character=CharacterRegistry.current(p);if(!p.isAlive()||character==null){message(p,"当前没有可用的在生人物；请完成正常进入或重生。");return false;}
        boolean joined=HouseholdData.get(p.server).addMember(NaturalVillageLifeData.home(b),character.id(),HouseholdRole.KIN,p.serverLevel().getGameTime());
        if(joined)HouseholdData.get(p.server).setProvisionPlan(new HouseholdProvisionPlan(NaturalVillageLifeData.home(b),0,0,0));
        message(p,joined?"已加入生活户。用 /saga society work 查看并接取当前木材缺口任务；四个橡木换两个实际胡萝卜。成员身份不授予领主权或私人资产处分权。":"已有家庭归属或家庭容量不足");return joined;
    }
    public static boolean leave(ServerPlayer p){
        var id=CharacterRegistry.currentId(p);if(!p.isAlive()||id==null){message(p,"当前没有可用的在生人物；请完成正常进入或重生。");return false;}var d=HouseholdData.get(p.server);var h=d.forMember(id);
        if(h==null||!d.removeMember(h.id(),id)){message(p,"没有可退出的生活户身份");return false;}
        NaturalVillageLifeData.get(p.server).cancelWork(id,h.settlementId());
        var natural=NaturalVillageLifeData.existing(p.server);
        if(natural!=null&&natural.villages().stream().filter(v->v.getBoolean("Ready")).map(NaturalVillageLifeData::decode).anyMatch(b->NaturalVillageLifeData.home(b).equals(h.id())))
            d.setProvisionPlan(new HouseholdProvisionPlan(h.id(),0,0,0));
        message(p,"已退出生活户；未提交任务已取消，已有结算和待核对义务保留，个人物品不变");return true;
    }
    private static int status(ServerPlayer p){
        var id=CharacterRegistry.currentId(p);if(id==null){message(p,"当前没有已绑定人物；成员查询不会创建人物。");return 0;}var h=HouseholdData.get(p.server).forMember(id);
        if(h==null){message(p,"尚未加入生活户；附近使用 /saga society nearby 和 join");return 0;}
        var paid=NaturalVillageLifeData.get(p.server).payment(id,h.settlementId());
        message(p,h.name()+" 普通成员；任务状态："+(paid==null?"尚未接取":stateText(paid.getString("State")))+"。用 /saga society work 接取，deliver 交付，cancel-work 取消未提交任务。");
        var b=nearest(p);if(b!=null&&h.id().equals(NaturalVillageLifeData.home(b)))describe(p,b);return 1;
    }
    private static String stateText(String state){return switch(state){case "ACCEPTED"->"已接取、尚未交付";case "PENDING"->"结算待确认";case "REVIEW"->"中断待核对，暂停重试";case "PAID"->"已交付并支付";case "FAILED"->"交付失败，物资保留";case "CANCELLED"->"未提交任务已取消";default->state;};}
    private static String unavailable(ServerPlayer p,NaturalVillageBuilding b){
        var s=p.server;var id=CharacterRegistry.currentId(p);if(!p.isAlive()||id==null)return "当前没有可用的在生人物";var h=HouseholdData.get(s).forMember(id);
        if(h==null||!h.id().equals(NaturalVillageLifeData.home(b)))return "需要先加入此处生活户";
        if(!Objects.equals(h.dimension(),b.dimension())||!Objects.equals(h.sharedStorage(),NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY)))return "家庭交付地点已变化；原任务保留，请取消未提交任务后重新确认";
        if(com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.controls(s,NaturalVillageLifeData.person(b,0)))return "聚落正在远方交接；等待原居民和账目返回后重试";
        var pantryPos=NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY);var publicPos=NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC);
        if(!p.serverLevel().hasChunkAt(pantryPos)||!p.serverLevel().hasChunkAt(publicPos))return "交付或报酬设施尚未加载";
        if(!(p.serverLevel().getBlockEntity(pantryPos) instanceof Container)||!(p.serverLevel().getBlockEntity(publicPos) instanceof Container goods))return "交付或报酬容器已不可用；个人货物和任务保留";
        var rights=RightsStateData.get(s);var pantry=rights.context(ContainerIds.block("household_storage",b.dimension(),pantryPos));var pub=rights.context(ContainerIds.block("natural_public",b.dimension(),publicPos));
        if(pantry==null||pub==null||!pantry.defaultBeneficialOwner().equals(SubjectRef.household(h.id()))||!pub.defaultBeneficialOwner().equals(SubjectRef.institution(b.settlementId())))return "交付容器权属变化；不支取或接管他人物资";
        // Existing fixed exchange lot replenishes a real public reserve, not an unconditional daily purchase.
        if(com.jgeted.sagadyssey.social.needs.SupplyFlowService.count(goods,new ItemFingerprint("minecraft:oak_log",0))>=4)return "公共仓已有至少四个橡木，目前没有本任务缺口；不收货、不扣粮";
        try{
            var point=com.jgeted.sagadyssey.social.needs.SupplyFlowData.get(s).point(pantry.containerId());
            int available=com.jgeted.sagadyssey.social.needs.SupplyFlowService.available(s,point,SubjectRef.household(h.id()),new ItemFingerprint("minecraft:carrot",0),p.serverLevel().getGameTime());
            if(NaturalVillageLayout.autonomousFarm(b)) {
                boolean severe=NaturalFarmRules.severelyHungry(s,b);
                if(!NaturalFarmRules.paymentAllowed(available,severe))return severe
                        ? "本户有居民严重饥饿，先保障居民进食；暂停新的报酬支付，不扣除你的货物"
                        : "支付两个胡萝卜后须保留六份合法未占用口粮；等待家庭补给，不扣除你的货物";
            } else if(available<2)return "没有两份可合法支配的实际报酬；等待家庭补给，不扣除你的货物";
        }catch(NoSuchElementException ex){return "家庭粮仓未正式登记，不以临时权限替代";}
        return null;
    }
    private static void describe(ServerPlayer p,NaturalVillageBuilding b){
        String why=unavailable(p,b);message(p,why==null?"当前公共木材缺口：交付四个橡木到 "+NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC).toShortString()+"，换本户两个胡萝卜；使用 deliver。接取不预留报酬，交付时复核；每人物每日最多一次成功结算。"
                +(NaturalVillageLayout.autonomousFarm(b)?"报酬支付后保留六份合法未占用口粮，居民紧急进食优先。":""):why);
    }
    private static boolean work(ServerPlayer p){
        var b=nearest(p);if(b==null){message(p,"附近没有可接取的自然聚落任务");return false;}
        String why=unavailable(p,b);if(why!=null){message(p,why);return false;}
        var id=CharacterRegistry.currentId(p);if(!p.isAlive()||id==null){message(p,"当前没有可用的在生人物；请完成正常进入或重生。");return false;}var data=NaturalVillageLifeData.get(p.server);
        if(data.acceptWork(id,b.settlementId(),p.serverLevel().getGameTime()/24000,b.buildingKey(),NaturalVillageLifeData.home(b))==null){message(p,"本日已经结算、已有待核对义务或原任务绑定不同地点；不重复接单");return false;}
        describe(p,b);return true;
    }
    private static boolean cancelWork(ServerPlayer p){
        var id=CharacterRegistry.currentId(p);if(!p.isAlive()||id==null){message(p,"当前没有可用的在生人物；请完成正常进入或重生。");return false;}var h=HouseholdData.get(p.server).forMember(id);
        boolean ok=h!=null&&NaturalVillageLifeData.get(p.server).cancelWork(id,h.settlementId());
        message(p,ok?"未提交任务已取消；没有扣除你的货物或预留报酬":"没有可取消的未提交任务；已付款及待核对义务不能据此删除");return ok;
    }
    private static int institutions(ServerPlayer p){var b=nearest(p);if(b==null)return 0;var d=com.jgeted.sagadyssey.social.politics.NaturalPolityData.existing(p.server);var t=d==null?null:d.site(b.settlementId());if(t==null)return 0;var leader=com.jgeted.sagadyssey.social.politics.PoliticalStateData.get(p.server).titleHolder(b.settlementId());var person=leader==null?null:com.jgeted.sagadyssey.social.character.CharacterRegistryData.get(p.server).byId(leader);String kind=t.getString("Kind").equals("GROUP")?"生活群体":t.contains("Realm")?"区域政体的成员村庄":"独立村庄";message(p,kind+"；现居人数 "+com.jgeted.sagadyssey.social.politics.NaturalPolityData.population(p.server,b).size()+"；负责人："+(person==null?"职位空缺":person.name())+"。"+t.getString("Reason"));if(t.contains("Project"))message(p,t.getCompound("Project").getString("Reason"));return 1;}
    private static int relief(ServerPlayer p){var b=nearest(p);if(b==null)return 0;var character=CharacterRegistry.current(p);if(!p.isAlive()||character==null){message(p,"当前没有可用的在生人物；请完成正常进入或重生。");return 0;}var home=HouseholdData.get(p.server).forMember(character.id());if(home==null||!Objects.equals(home.settlementId(),b.settlementId())||p.getFoodData().getFoodLevel()>10){message(p,"本户成员在饥饿时可申请有限的公共食物援助");return 0;}var d=com.jgeted.sagadyssey.social.politics.NaturalPolityData.get(p.server);var leader=com.jgeted.sagadyssey.social.politics.PoliticalStateData.get(p.server).titleHolder(b.settlementId());String result=d.relief(p.server,b,leader,home.id());message(p,result.equals("DONE")?"负责人已核验申请；两份实际公共食物已交本户粮仓，本日重复申请沿用原结果。":result.equals("REVIEW")?"上次救济仍待核对，暂不再次支取。":result.equals("FAILED")?"本次实际支取未能完成，物资保留。":result);return result.equals("DONE")?1:0;}
    private static int peace(ServerPlayer p){var character=CharacterRegistry.current(p);if(!p.isAlive()||character==null){message(p,"当前没有可用的在生人物；请完成正常进入或重生。");return 0;}var home=HouseholdData.get(p.server).forMember(character.id());if(home==null)return 0;var d=com.jgeted.sagadyssey.social.combat.NaturalConflictData.existing(p.server);if(d==null)return 0;boolean accepted=d.petitionPeace(p.server,home.id());message(p,accepted?"双方负责人接受止损请求，原行动停止；伤口、实际成本和未付义务继续保留。":"目前没有符合双方止损条件的行动。");return accepted?1:0;}
    public static boolean deliver(ServerPlayer p){
        var b=nearest(p);if(b==null){message(p,"请到原聚落附近交付；个人货物保持不变");return false;}
        var character=CharacterRegistry.current(p);if(!p.isAlive()||character==null){message(p,"当前没有可用的在生人物；请完成正常进入或重生。");return false;}var houses=HouseholdData.get(p.server);
        var h=houses.forMember(character.id());if(h==null||!h.id().equals(NaturalVillageLifeData.home(b))){message(p,"你不是此处生活户成员，不能领取其报酬");return false;}
        var level=p.serverLevel();long now=level.getGameTime();var payments=NaturalVillageLifeData.get(p.server);var ledger=TransferLedgerData.get(p.server);
        var previous=payments.payment(character.id(),b.settlementId());
        if(previous!=null&&previous.getString("State").equals("PENDING")){
            var receipt=ledger.receipt(previous.getUUID("Transaction"));payments.finishPayment(character.id(),b.settlementId(),receipt==null?"REVIEW":receipt.successful()?"PAID":"FAILED");
            previous=payments.payment(character.id(),b.settlementId());
        }
        if(previous!=null&&(previous.getString("State").equals("REVIEW")||previous.getString("State").equals("PAID")&&previous.getLong("Day")>=now/24000)){message(p,"交付已结算或有待核对义务，不能重复领取");return false;}
        if(previous!=null&&previous.getString("State").equals("ACCEPTED")&&(!b.buildingKey().equals(previous.getString("Building"))||!h.id().equals(previous.getUUID("Household")))){message(p,"原任务绑定不同家庭或地点；个人货物保留，不重定向结算");return false;}
        String why=unavailable(p,b);if(why!=null){message(p,why);return false;}
        var pantryPos=NaturalVillageLayout.at(b,NaturalVillageLayout.PANTRY);var publicPos=NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC);
        var food=(Container)level.getBlockEntity(pantryPos);var goods=(Container)level.getBlockEntity(publicPos);
        UUID transaction=payments.beginPayment(character.id(),b.settlementId(),now/24000);
        if(transaction==null){message(p,"交付已结算或有待核对义务，不能重复领取");return false;}
        var rights=RightsStateData.get(p.server);var actor=SubjectRef.character(character.id());UUID bag=ContainerIds.playerPersonal(character.id());
        rights.ensureContext(bag,"player:"+character.id(),actor,actor,AccessPolicy.PRIVATE);
        UUID pantry=ContainerIds.block("household_storage",b.dimension(),pantryPos),publicId=ContainerIds.block("natural_public",b.dimension(),publicPos);
        var request=new TransferRequest(transaction,actor,TransferIntent.PAYMENT,
                List.of(new TransferLeg(bag,publicId,new ItemFingerprint("minecraft:oak_log",0),4),new TransferLeg(pantry,bag,new ItemFingerprint("minecraft:carrot",0),2)),
                List.of(new ExpectedRevision(bag,rights.context(bag).revision()),new ExpectedRevision(publicId,rights.context(publicId).revision()),new ExpectedRevision(pantry,rights.context(pantry).revision())),
                "one actual delivery per character/day",now,Map.of(pantry,SubjectRef.household(h.id())));
        var result=new TransferService(rights,ledger).submit(request,Map.of(bag,new TransferEndpoint(bag,p.getInventory()),pantry,new TransferEndpoint(pantry,food),publicId,new TransferEndpoint(publicId,goods)),now);
        payments.finishPayment(character.id(),b.settlementId(),result.successful()?"PAID":"FAILED");
        message(p,result.successful()?"四个橡木已入库，两个胡萝卜报酬已到账":"交付未完成：库存、权限或容量不足，双方物资保留");return result.successful();
    }
}
