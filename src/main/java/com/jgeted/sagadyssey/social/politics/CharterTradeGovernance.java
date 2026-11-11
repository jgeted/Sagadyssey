package com.jgeted.sagadyssey.social.politics;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import java.util.*;

/** One voluntary, fixed reserve charter. Storage is the existing sites/decisions ledger. */
public final class CharterTradeGovernance {
    private static final ItemFingerprint FOOD=new ItemFingerprint("minecraft:carrot",0);
    public record Outcome(boolean accepted,String state,String reason) {}
    private static Outcome ok(String state,String reason){return new Outcome(true,state,reason);}
    private static Outcome denied(String why){return new Outcome(false,"DENIED",why);}
    private static long now(MinecraftServer s){return s.overworld().getGameTime();}
    private static NaturalPolityData data(MinecraftServer s){return NaturalPolityData.get(s);}
    public static UUID actor(Entity e){return e instanceof NpcBase n?n.getCharacterId():e instanceof ServerPlayer p&&p.isAlive()?CharacterRegistry.currentId(p):null;}
    private static boolean representative(MinecraftServer s,UUID site,UUID actor){
        var d=data(s);var row=d.governanceSite(site);if(row==null||actor==null)return false;
        var b=NaturalPolityData.building(row);var h=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));
        return NaturalVillageLayout.chartered(b)&&h!=null&&actor.equals(h.headCharacterId())&&d.authorized(s,b,actor);
    }
    private static boolean enabled(CompoundTag row){return row!=null&&row.getCompoundOrEmpty("ReserveRule").getBooleanOr("Enabled", false);}
    public static UUID pactId(UUID a,UUID b){return ContainerIds.named("reserve-charter:v1:"+(a.toString().compareTo(b.toString())<0?a+"/"+b:b+"/"+a));}
    public static CompoundTag pact(MinecraftServer s,UUID a,UUID b){var t=data(s).governanceDecision(pactId(a,b));return t==null?null:t.copy();}
    private static String side(CompoundTag pact,UUID site){return pact.read("A", UUIDUtil.CODEC).orElseThrow().equals(site)?"A":pact.read("B", UUIDUtil.CODEC).orElseThrow().equals(site)?"B":null;}
    private static int publicFree(MinecraftServer s,NaturalVillageBuilding b){
        var inventory=NaturalPolityData.inventory(s,b,3);var id=com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.storeId(b,3);var owner=SubjectRef.institution(b.settlementId());
        var context=RightsStateData.get(s).context(id);if(inventory==null||context==null||!context.defaultBeneficialOwner().equals(owner))return -1;
        return SupplyFlowService.available(s,new FieldworkStorage(b.dimension(),NaturalVillageLayout.at(b,NaturalVillageLayout.PUBLIC),id,owner,AccessPolicy.PRIVATE,TransferIntent.DELIVERY,"own public reserve"),owner,FOOD,now(s));
    }
    public static Outcome enable(MinecraftServer s,UUID self,UUID actor){
        if(!representative(s,self,actor))return denied("仅本聚落现任公职兼本生活户户主可自愿加入，不代表其他居民处分私产");
        var d=data(s);var row=d.governanceSite(self);if(enabled(row))return ok("ENABLED","原储备规则已启用，重复申请不新增决定");
        var rule=new CompoundTag();rule.putInt("Version",1);rule.putBoolean("Enabled",true);rule.store("AdoptedBy", UUIDUtil.CODEC,actor);rule.putLong("AdoptedAt",now(s));rule.putString("State","PENDING");rule.putString("Reason","等待正式检查本机构自由公共储备");rule.putInt("Revision",0);row.put("ReserveRule",rule);d.setDirty();return ok("ENABLED","已自愿启用固定公共储备规则；状态由真实库存检查产生");
    }
    private static void observe(MinecraftServer s,NaturalPolityData d,CompoundTag row){if(!enabled(row))return;
        var b=NaturalPolityData.building(row);var r=row.getCompoundOrEmpty("ReserveRule");int free=publicFree(s,b);long now=now(s);UUID holder=PoliticalStateData.get(s).titleHolder(b.settlementId());
        boolean authority=representative(s,b.settlementId(),holder);String state=r.getStringOr("State", ""),next=state;
        if(!authority||free<0){next="CLOSED";r.remove("GoodSince");}
        else if(free<=6){next="CLOSED";r.remove("GoodSince");}
        else if(!state.equals("OPEN")){
            if(free>=8){if(!r.contains("GoodSince"))r.putLong("GoodSince",now);next=now-r.getLongOr("GoodSince", 0L)>=200?"OPEN":"CLOSED";}
            else{r.remove("GoodSince");next="CLOSED";}
        }
        r.putInt("FreeCarrots",free);r.putLong("ObservedAt",now);r.store("CheckedBy", UUIDUtil.CODEC,holder==null?new UUID(0,0):holder);
        r.putString("Reason",!authority?"现任公职或自愿生活户代表授权失效":free<0?"公共仓未加载或机构产权不符":next.equals("OPEN")?"本机构自由储备已满足开放条件":"公共自由胡萝卜须超过6，恢复须至少8连续200刻；现为"+free);
        if(!next.equals(state)){
            int revision=r.getIntOr("Revision", 0)+1;var decision=new CompoundTag();decision.store("Id", UUIDUtil.CODEC,ContainerIds.named("reserve-rule:v1:"+b.settlementId()+":"+revision));decision.putString("Kind","RESERVE_DECISION");decision.store("Site", UUIDUtil.CODEC,b.settlementId());decision.store("Actor", UUIDUtil.CODEC,holder==null?new UUID(0,0):holder);decision.putInt("Revision",revision);decision.putString("State",next);decision.putInt("FreeCarrots",free);decision.putString("Reason",r.getStringOr("Reason", ""));decision.putLong("At",now);d.governanceRecord(decision);r.putInt("Revision",revision);r.putString("State",next);
        }d.setDirty();
    }
    private static void history(CompoundTag t,String action,UUID actor,long at){var list=MinecraftApi.listOfType(t, "History",Tag.TAG_COMPOUND);var e=new CompoundTag();e.putString("Action",action);if(actor!=null)e.store("Actor", UUIDUtil.CODEC,actor);e.putLong("At",at);e.putInt("Epoch",t.getIntOr("Epoch", 0));list.add(e);while(list.size()>64)list.remove(0);t.put("History",list);}
    private static void pause(NaturalPolityData d,CompoundTag t,String reason,long at){
        if(!t.getStringOr("State", "").equals("PAUSED")||t.read("SignedA", UUIDUtil.CODEC).isPresent()||t.read("SignedB", UUIDUtil.CODEC).isPresent()){
            t.putInt("Epoch",t.getIntOr("Epoch", 0)+1);t.remove("SignedA");t.remove("SignedB");history(t,"PAUSE",null,at);
        }t.putString("State","PAUSED");t.putString("Reason",reason);d.setDirty();
    }
    private static void assessPact(MinecraftServer s,NaturalPolityData d,CompoundTag t){
        var a=d.governanceSite(t.read("A", UUIDUtil.CODEC).orElseThrow());var b=d.governanceSite(t.read("B", UUIDUtil.CODEC).orElseThrow());
        if(!enabled(a)||!enabled(b)||!a.getCompoundOrEmpty("ReserveRule").getStringOr("State", "").equals("OPEN")||!b.getCompoundOrEmpty("ReserveRule").getStringOr("State", "").equals("OPEN")){
            pause(d,t,"约定的公共储备或现任权威条件未满足；仅暂停新承诺，旧债与货物保留",now(s));return;
        }
        for(String side:List.of("A","B"))if(t.read("Signed"+side, UUIDUtil.CODEC).isPresent()&&!representative(s,t.read(side, UUIDUtil.CODEC).orElseThrow(),t.read("Signed"+side, UUIDUtil.CODEC).orElseThrow())){pause(d,t,"原批准主体已无本方现任公职及户主授权，须双方重新批准",now(s));return;}
        if(t.getBooleanOr("WithdrawA", false)||t.getBooleanOr("WithdrawB", false)){t.putString("State","PAUSED");t.putString("Reason","一方撤回本方同意；未代表另一方作决定");d.setDirty();return;}
        String next=t.read("SignedA", UUIDUtil.CODEC).isPresent()&&t.read("SignedB", UUIDUtil.CODEC).isPresent()?"ACTIVE":"PENDING";
        if(!next.equals(t.getStringOr("State", "")))history(t,next,null,now(s));t.putString("State",next);t.putString("Reason",next.equals("ACTIVE")?"当前纪元双方现任合法代表分别批准；不提供物权或库存权限":"等待当前纪元双方分别批准；一方不能冒充另一方");d.setDirty();
    }
    public static Outcome agreement(MinecraftServer s,UUID self,UUID peer,UUID actor,String action){
        if(self.equals(peer)||!representative(s,self,actor))return denied("没有本方独立批准或暂停本约定的权力");
        var d=data(s);var a=d.governanceSite(self);var b=d.governanceSite(peer);if(!enabled(a)||!enabled(b))return denied("双方尚未自愿启用该章程");
        var ba=NaturalPolityData.building(a);var bb=NaturalPolityData.building(b);if(!ba.dimension().equals(bb.dimension())||ba.buildingOrigin().distSqr(bb.buildingOrigin())>192*192)return denied("仅支持同维度192格以内的已知聚落");
        observe(s,d,a);observe(s,d,b);UUID id=pactId(self,peer);var t=d.governanceDecision(id);
        if(action.equals("propose")){
            if(t!=null)return ok(t.getStringOr("State", ""),"原提议已存在，重复提议不代替重新批准");
            t=new CompoundTag();t.store("Id", UUIDUtil.CODEC,id);t.putString("Kind","RESERVE_PACT");t.putInt("Version",1);t.store("A", UUIDUtil.CODEC,self);t.store("B", UUIDUtil.CODEC,peer);t.store("HomeA", UUIDUtil.CODEC,NaturalVillageLifeData.home(ba));t.store("HomeB", UUIDUtil.CODEC,NaturalVillageLifeData.home(bb));t.putInt("Epoch",1);t.putString("State","PENDING");t.store("SignedA", UUIDUtil.CODEC,actor);history(t,"PROPOSE_A",actor,now(s));d.governanceRecord(t);assessPact(s,d,t);return ok(t.getStringOr("State", ""),t.getStringOr("Reason", ""));
        }
        if(t==null)return denied("还没有本双方的正式提议");String side=side(t,self);
        if(action.equals("pause")){
            if(!t.getBooleanOr("Withdraw"+side, false)){t.putBoolean("Withdraw"+side,true);pause(d,t,"本方依法撤回同意；保留旧履约",now(s));history(t,"WITHDRAW_"+side,actor,now(s));}return ok("PAUSED","仅撤回本方同意，没有代替另一方批准");
        }
        if(!action.equals("approve"))return denied("未知约定操作");
        if(!a.getCompoundOrEmpty("ReserveRule").getStringOr("State", "").equals("OPEN")||!b.getCompoundOrEmpty("ReserveRule").getStringOr("State", "").equals("OPEN"))return denied("储备条件尚未恢复，不能批准开放新承诺");
        if(!t.read("Signed"+side, UUIDUtil.CODEC).isPresent()||!t.read("Signed"+side, UUIDUtil.CODEC).orElseThrow().equals(actor)||t.getBooleanOr("Withdraw"+side, false)){
            t.store("Signed"+side, UUIDUtil.CODEC,actor);t.putBoolean("Withdraw"+side,false);history(t,"APPROVE_"+side,actor,now(s));d.setDirty();
        }assessPact(s,d,t);return ok(t.getStringOr("State", ""),t.getStringOr("Reason", ""));
    }
    public static Outcome publicAdmission(MinecraftServer s,NaturalVillageBuilding b){var d=data(s);var row=d.governanceSite(b.settlementId());if(!enabled(row))return ok("OPEN","原公共处分规则");observe(s,d,row);return row.getCompoundOrEmpty("ReserveRule").getStringOr("State", "").equals("OPEN")?ok("OPEN","储备规则允许新公共支出"):denied("公共储备政策拒绝新的支出："+row.getCompoundOrEmpty("ReserveRule").getStringOr("Reason", ""));}
    public static Outcome admission(MinecraftServer s,NaturalVillageBuilding buyer,NaturalVillageBuilding seller){
        var d=data(s);var a=d.governanceSite(buyer.settlementId());var b=d.governanceSite(seller.settlementId());if(!enabled(a)&&!enabled(b))return ok("OPEN","沿用未加入新章程的P5原规则");
        if(!enabled(a)||!enabled(b))return denied("另一方尚未自愿加入本双边章程");observe(s,d,a);observe(s,d,b);
        if(!a.getCompoundOrEmpty("ReserveRule").getStringOr("State", "").equals("OPEN")||!b.getCompoundOrEmpty("ReserveRule").getStringOr("State", "").equals("OPEN"))return denied("公共储备决定暂停新交易；旧债与在途不变");
        var t=d.governanceDecision(pactId(buyer.settlementId(),seller.settlementId()));if(t==null)return denied("尚无双方分别批准的约定");assessPact(s,d,t);
        return t.getStringOr("State", "").equals("ACTIVE")?ok("ACTIVE","仅允许继续核验P5独立准入条件"):denied(t.getStringOr("Reason", ""));
    }
    public static void refusal(MinecraftServer s,NaturalVillageBuilding buyer,NaturalVillageBuilding seller,String reason){
        var d=data(s);var row=d.governanceSite(buyer.settlementId());if(row==null||!enabled(row))return;
        var t=new CompoundTag();t.store("Buyer", UUIDUtil.CODEC,buyer.settlementId());t.store("Seller", UUIDUtil.CODEC,seller.settlementId());t.putString("Reason",reason);t.putLong("At",now(s));row.put("LastTradeRefusal",t);d.setDirty();
    }
    public static Outcome donate(MinecraftServer s,UUID self,Entity entity){
        UUID actor=actor(entity);var d=data(s);var row=d.governanceSite(self);if(actor==null||!enabled(row))return denied("本人尚未加入本聚落的储备章程");
        var home=HouseholdData.get(s).forMember(actor);if(home==null||!self.equals(home.settlementId()))return denied("只能自愿向本聚落公共机构捐助本人物品");
        var b=NaturalPolityData.building(row);Container bag=entity instanceof NpcBase n?n.getPersonalInventory():entity instanceof ServerPlayer p?p.getInventory():null;
        UUID from=entity instanceof ServerPlayer?ContainerIds.playerPersonal(actor):ContainerIds.npcPersonal(actor),to=com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData.storeId(b,3);var target=NaturalPolityData.inventory(s,b,3);var rights=RightsStateData.get(s);
        if(bag==null||target==null||rights.context(from)==null||rights.context(to)==null||!rights.context(to).defaultBeneficialOwner().equals(SubjectRef.institution(self)))return denied("本人的真实库存或本机构仓储不可用");
        UUID id=ContainerIds.named("reserve-donation:v1:"+self+":"+actor+":"+now(s)/24000);var prior=d.governanceDecision(id);if(prior!=null)return new Outcome(prior.getStringOr("State", "").equals("DONE"),prior.getStringOr("State", ""),"沿用原自愿捐助结果，不重复扣物");
        var t=new CompoundTag();t.store("Id", UUIDUtil.CODEC,id);t.putString("Kind","RESERVE_DONATION");t.store("Actor", UUIDUtil.CODEC,actor);t.store("Site", UUIDUtil.CODEC,self);t.putInt("Quantity",2);t.putString("State","SUBMITTING");t.putLong("At",now(s));d.governanceRecord(t);
        var request=new TransferRequest(id,SubjectRef.character(actor),TransferIntent.GIFT,List.of(new TransferLeg(from,to,FOOD,2)),List.of(new ExpectedRevision(from,rights.context(from).revision()),new ExpectedRevision(to,rights.context(to).revision())),"本人自愿捐助两份公共储备，不改变其他私产权",now(s));
        var receipt=new TransferService(rights,TransferLedgerData.get(s)).submit(request,Map.of(from,new TransferEndpoint(from,bag),to,new TransferEndpoint(to,target)),now(s));t.putString("State",receipt.successful()?"DONE":"FAILED");t.putString("Reason",receipt.reason());d.setDirty();return new Outcome(receipt.successful(),t.getStringOr("State", ""),receipt.successful()?"本人两份实物已合法捐入本机构，重复操作沿用原结果":receipt.reason());
    }
    public static void tick(MinecraftServer s,NaturalPolityData d){for(var row:d.sites())if(enabled(row))observe(s,d,d.governanceSite(NaturalPolityData.building(row).settlementId()));for(var t:d.governanceDecisions())if(t.getStringOr("Kind", "").equals("RESERVE_PACT"))assessPact(s,d,t);}
    static void validate(NaturalPolityData d){
        for(var row:d.sites()){var r=row.getCompoundOrEmpty("ReserveRule");if(row.contains("ReserveRule")&&(r.getIntOr("Version", 0)!=1||!r.read("AdoptedBy", UUIDUtil.CODEC).isPresent()||!Set.of("PENDING","OPEN","CLOSED").contains(r.getStringOr("State", ""))||r.getIntOr("Revision", 0)<0))throw new IllegalArgumentException("Invalid fixed reserve policy");}
        for(var t:d.governanceDecisions())if(t.getStringOr("Kind", "").equals("RESERVE_PACT")){
            if(t.getIntOr("Version", 0)!=1||!t.read("A", UUIDUtil.CODEC).isPresent()||!t.read("B", UUIDUtil.CODEC).isPresent()||t.read("A", UUIDUtil.CODEC).orElseThrow().equals(t.read("B", UUIDUtil.CODEC).orElseThrow())||!t.read("Id", UUIDUtil.CODEC).orElseThrow().equals(pactId(t.read("A", UUIDUtil.CODEC).orElseThrow(),t.read("B", UUIDUtil.CODEC).orElseThrow()))||!Set.of("PENDING","ACTIVE","PAUSED").contains(t.getStringOr("State", ""))||t.getIntOr("Epoch", 0)<1||d.governanceSite(t.read("A", UUIDUtil.CODEC).orElseThrow())==null||d.governanceSite(t.read("B", UUIDUtil.CODEC).orElseThrow())==null)throw new IllegalArgumentException("Invalid bounded bilateral charter");
        }
    }
}
