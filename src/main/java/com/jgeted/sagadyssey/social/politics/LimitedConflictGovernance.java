package com.jgeted.sagadyssey.social.politics;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.social.combat.NaturalConflictData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Independent consent for one existing prepaid dispute. It grants no inventory rights. */
public final class LimitedConflictGovernance {
    public record Outcome(boolean accepted,String reason) {}
    private static final ItemFingerprint FOOD=new ItemFingerprint("minecraft:carrot",0);
    private static UUID key(UUID deal){return ContainerIds.named("limited-conflict-consent:v1:"+deal);}
    private static CompoundTag row(MinecraftServer s,UUID deal){return NaturalPolityData.get(s).governanceDecision(key(deal));}
    public static CompoundTag record(MinecraftServer s,UUID deal){var t=row(s,deal);return t==null?null:t.copy();}
    private static CompoundTag deal(MinecraftServer s,UUID id){var d=SettlementExchangeData.existing(s);return d==null?null:d.deals().stream().filter(t->t.getUUID("Id").equals(id)).findFirst().orElse(null);}
    private static NaturalVillageBuilding village(CompoundTag d,String side){return NaturalVillageLifeData.decode(d.getCompound(side.equals("A")?"Buyer":"Seller"));}
    private static String side(CompoundTag t,UUID self){return self.equals(t.getUUID("A"))?"A":self.equals(t.getUUID("B"))?"B":null;}
    private static boolean authorized(MinecraftServer s,NaturalVillageBuilding b,UUID actor){var home=HouseholdData.get(s).byId(NaturalVillageLifeData.home(b));return actor!=null&&home!=null&&actor.equals(home.headCharacterId())&&NaturalPolityData.get(s).authorized(s,b,actor);}
    private static void event(CompoundTag t,String action,UUID actor,long now){var h=t.getList("History",Tag.TAG_COMPOUND);var e=new CompoundTag();e.putString("Action",action);e.putUUID("Actor",actor);e.putLong("At",now);h.add(e);t.put("History",h);}
    private static Outcome denied(String why){return new Outcome(false,why);}
    public static boolean managed(MinecraftServer s,UUID id){return row(s,id)!=null;}
    static void validate(NaturalPolityData data){
        for(var t:data.governanceDecisions())if(t.getString("Kind").equals("LIMITED_CONFLICT_CONSENT")){
            if(t.getInt("Version")!=1||!t.hasUUID("Deal")||!t.getUUID("Id").equals(key(t.getUUID("Deal")))||!t.hasUUID("A")||!t.hasUUID("B")||t.getUUID("A").equals(t.getUUID("B"))||t.getBoolean("Truce")&&(!t.hasUUID("PeaceA")||!t.hasUUID("PeaceB")||!t.contains("TruceAt")))throw new IllegalArgumentException("Invalid limited conflict consent");
        }
    }
    public static boolean awaitingPeace(MinecraftServer s,UUID id){var r=row(s,id);return r!=null&&r.getBoolean("Declared")&&!r.getBoolean("Truce")&&!r.getBoolean("Withdrawn");}
    private static boolean protectedPair(MinecraftServer s,UUID a,UUID b){
        for(var t:NaturalPolityData.get(s).governanceDecisions())if(t.getString("Kind").equals("LIMITED_CONFLICT_CONSENT")&&t.getBoolean("Truce")&&(a.equals(t.getUUID("A"))&&b.equals(t.getUUID("B"))||a.equals(t.getUUID("B"))&&b.equals(t.getUUID("A"))))return true;
        return false;
    }
    public static boolean admitted(MinecraftServer s,CompoundTag deal,UUID actor){
        if(protectedPair(s,village(deal,"A").settlementId(),village(deal,"B").settlementId()))return false;
        var r=row(s,deal.getUUID("Id"));if(r==null)return true;
        if(actor==null||!r.getBoolean("Declared")||r.getBoolean("Truce")||r.getBoolean("Withdrawn")||!r.hasUUID("PreparedA")||!r.hasUUID("PreparedB")||!actor.equals(r.getUUID("PreparedA")))return false;
        return authorized(s,village(deal,"A"),r.getUUID("PreparedA"))&&authorized(s,village(deal,"B"),r.getUUID("PreparedB"));
    }
    public static Outcome action(MinecraftServer s,UUID self,UUID peer,UUID id,UUID actor,String action){
        var d=deal(s,id);if(d==null)return denied("须引用同一笔真实交换义务");
        var a=village(d,"A");var b=village(d,"B");
        if(self.equals(peer)||!(self.equals(a.settlementId())&&peer.equals(b.settlementId())||self.equals(b.settlementId())&&peer.equals(a.settlementId())))return denied("不是这笔义务的两个独立聚落");
        var own=self.equals(a.settlementId())?a:b;
        if(!a.dimension().equals(b.dimension())||a.buildingOrigin().distSqr(b.buildingOrigin())>192*192||!authorized(s,own,actor))return denied("缺少本方现任公职、原生活户户主或有界场景权限");
        var data=NaturalPolityData.get(s);var t=row(s,id);long now=s.overworld().getGameTime();
        if((action.equals("prepare")||action.equals("declare"))&&protectedPair(s,a.settlementId(),b.settlementId()))return denied("这两个聚落已有生效停战，新义务编号不能绕过约定重新动员");
        if(action.equals("prepare")){
            if(t==null){t=new CompoundTag();t.putUUID("Id",key(id));t.putString("Kind","LIMITED_CONFLICT_CONSENT");t.putInt("Version",1);t.putUUID("Deal",id);t.putUUID("A",a.settlementId());t.putUUID("B",b.settlementId());data.governanceRecord(t);}
            String side=side(t,self);if(t.hasUUID("Prepared"+side))return t.getUUID("Prepared"+side).equals(actor)?new Outcome(true,"沿用本方原准备，不重复预留"):denied("原准备责任尚未核对，不能替换人物");
            if(t.getBoolean("Declared")||t.getBoolean("Truce")||t.getBoolean("Withdrawn"))return denied("本轮已进入行动或退出，不新增准备");
            var inventory=NaturalPolityData.inventory(s,own,1);var cid=NaturalVillageEconomyData.storeId(own,1);var owner=SubjectRef.household(NaturalVillageLifeData.home(own));
            if(inventory==null||SupplyFlowService.available(s,SupplyFlowData.get(s).point(cid),owner,FOOD,now)<2)return denied("本户没有两份有权且未承诺的行动口粮；不动用私存或公共物资");
            var lease=new AssetReservation(ContainerIds.named("limited-rations:"+id+":"+self),cid,FOOD,2,owner,"dispute-rations:"+id,"本生活户负责人同意的有限行动口粮",now,now+96000);
            if(!RightsStateData.get(s).reserve(lease,SupplyFlowService.count(inventory,FOOD),now))return denied("原有预留冲突");
            t.putUUID("Prepared"+side,actor);event(t,"PREPARE_"+side,actor,now);data.setDirty();return new Outcome(true,"仅本方两份真实口粮已预留；尚未宣战或代对方同意");
        }
        if(t==null)return denied("尚未建立本轮双方独立准备");String side=side(t,self);
        if(action.equals("declare")){
            if(!side.equals("A"))return denied("只有真实预付未获交付的一方可提出本轮有限追索");
            if(t.getBoolean("Truce")||t.getBoolean("Withdrawn"))return denied("停战或退出已约束本轮旧矛盾，不能重新动员");
            if(!t.hasUUID("PreparedA")||!t.hasUUID("PreparedB"))return denied("尚缺本方或对方自卫准备，不能代扣另一户口粮");
            if(!t.getBoolean("Declared")){t.putBoolean("Declared",true);event(t,"DECLARE_A",actor,now);data.setDirty();}
            String state=NaturalConflictData.get(s).respond(s,id,actor);return new Outcome(state.equals("ACTIVE")||state.equals("CEASEFIRE"),state);
        }
        var war=NaturalConflictData.get(s).disputes().stream().filter(x->x.getUUID("Id").equals(id)).findFirst().orElse(null);
        if(action.equals("cancel")){
            if(war!=null)return denied("行动已有真实成本或待核对事实，不能取消清账");
            if(!t.hasUUID("Prepared"+side)||!actor.equals(t.getUUID("Prepared"+side)))return denied("没有本方原准备权威");
            RightsStateData.get(s).consumeReservation("dispute-rations:"+id,NaturalVillageEconomyData.storeId(own,1),FOOD,2);t.remove("Prepared"+side);event(t,"CANCEL_"+side,actor,now);data.setDirty();return new Outcome(true,"仅释放本方未用准备，不退款、不清旧债");
        }
        if(war==null)return denied("本轮尚未发生实际行动");
        if(action.equals("withdraw")){
            if(t.getBoolean("Truce"))return new Outcome(true,"沿用原停战，不重放撤离");
            if(!t.getBoolean("Withdrawn")){NaturalConflictData.get(s).withdraw(s,id,"本方现任合法主体撤回本机构行动；不冒充双边停战");t.putBoolean("Withdrawn",true);event(t,"WITHDRAW_"+side,actor,now);data.setDirty();}return new Outcome(true,"停止行动，保留原位置、伤口、成本和欠付；双边同意未伪造");
        }
        if(!action.equals("peace-propose")&&!action.equals("peace-accept"))return denied("未知冲突决定");
        if(t.getBoolean("Truce"))return new Outcome(true,"沿用原双方停战，不重复执行");
        if(now-war.getLong("Started")<1200)return denied("尚未达到已声明的止损协商时限");
        String other=side.equals("A")?"B":"A";
        if(action.equals("peace-accept")&&!t.hasUUID("Peace"+other))return denied("没有对方的独立停战提议，不能自提自批冒充双方");
        if(!t.hasUUID("Peace"+side)){t.putUUID("Peace"+side,actor);event(t,action.toUpperCase(Locale.ROOT)+"_"+side,actor,now);data.setDirty();}
        if(t.hasUUID("PeaceA")&&t.hasUUID("PeaceB")){
            if(!authorized(s,a,t.getUUID("PeaceA"))||!authorized(s,b,t.getUUID("PeaceB")))return denied("原停战签署人已失去本方授权，保留记录待核对");
            NaturalConflictData.get(s).stop(s,id,"两方当前合法负责人分别提出和接受有限停战");t.putBoolean("Truce",true);t.putLong("TruceAt",now);event(t,"BILATERAL_TRUCE",actor,now);data.setDirty();return new Outcome(true,"双边停战生效；禁止重新追索，原债务与P6贸易条件分别保留");
        }
        return new Outcome(true,"只有本方签署，尚无双边停战");
    }
}
