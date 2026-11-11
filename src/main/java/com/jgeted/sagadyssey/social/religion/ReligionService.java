package com.jgeted.sagadyssey.social.religion;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.knowledge.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.*;
import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

/** Actual consent, accountable office checks, real assets and knower-scoped event evidence. */
public final class ReligionService{
    private ReligionService(){}
    @javax.annotation.Nullable public static UUID currentCharacter(LivingEntity e){if(!e.isAlive())return null;if(e instanceof NpcBase n)return n.getCharacterId();if(e instanceof ServerPlayer p)return CharacterRegistry.currentId(p);return null;}
    public static UUID character(LivingEntity e){if(e instanceof NpcBase n)return n.getCharacterId();if(e instanceof ServerPlayer p)return CharacterRegistry.ensureCurrent(p).id();throw new IllegalArgumentException("persistent character required");}
    public static Container inventory(LivingEntity e){if(e instanceof NpcBase n)return n.getPersonalInventory();if(e instanceof ServerPlayer p)return p.getInventory();throw new IllegalArgumentException("real personal inventory required");}
    public static UUID personalContainer(LivingEntity e){UUID id=character(e);return e instanceof ServerPlayer?ContainerIds.playerPersonal(id):ContainerIds.npcPersonal(id);}
    public static LivingEntity body(MinecraftServer server,UUID character){
        var registry=CharacterRegistryData.get(server);var npc=registry.findLoadedNpc(server,character);if(npc!=null&&npc.isAlive())return npc;
        for(var p:server.getPlayerList().getPlayers())if(p.isAlive()&&character.equals(CharacterRegistry.currentId(p)))return p;return null;
    }
    public static boolean near(LivingEntity e,TempleRecord t){return e.isAlive()&&e.level().dimension().identifier().toString().equals(t.alms().dimension())&&e.distanceToSqr(t.counter().getX()+.5,t.counter().getY(),t.counter().getZ()+.5)<=64;}
    public static boolean authorized(NpcBase npc,UUID temple,OfficeAuthority power){return InstitutionData.get(MinecraftApi.server(npc)).authority(SubjectRef.character(npc.getCharacterId()),temple,power,npc.level().getGameTime()).allowed();}
    public static DonationRecord donate(MinecraftServer server,UUID id,LivingEntity donor,TempleRecord temple,ItemFingerprint item,int quantity,DonationRecord.Purpose purpose){
        UUID character=character(donor),source=personalContainer(donor);var target=purpose==DonationRecord.Purpose.ALMS?temple.alms():temple.treasury();long now=server.overworld().getGameTime();var d=ReligionData.get(server);
        var previous=d.donation(id);
        if(previous!=null){if(!previous.temple().equals(temple.id())||!previous.donor().equals(character)||!previous.source().equals(source)||!previous.destination().equals(target.containerId())||!previous.item().equals(item)||previous.quantity()!=quantity||previous.purpose()!=purpose)throw new IllegalStateException("donation id reused with different consent");
            if(previous.status()==DonationRecord.Status.PENDING){var receipt=TransferLedgerData.get(server).receipt(id);return finishDonation(server,previous,receipt,now);}return previous;}
        if(TransferLedgerData.get(server).receipt(id)!=null)throw new IllegalStateException("transfer id already belongs to another consent or domain");
        if(!near(donor,temple)||!interacting(donor,target.position()))throw new IllegalStateException("donation requires actual proximity to the store");
        var box=SupplyFlowService.container(server,target);if(box==null||!SupplyFlowService.validOwner(server,target))throw new IllegalStateException("donation endpoint unavailable or title changed");
        var rights=RightsStateData.get(server);var actor=SubjectRef.character(character);rights.ensureContext(source,"character:personal",actor,actor,AccessPolicy.PRIVATE);
        var gift=new DonationRecord(id,temple.id(),character,source,target.containerId(),item,quantity,purpose,DonationRecord.Status.PENDING,rights.context(source).revision(),rights.context(target.containerId()).revision(),now,now,"明确自愿捐赠，尚未扣物");d.donation(gift);
        var request=new TransferRequest(id,actor,TransferIntent.GIFT,List.of(new TransferLeg(source,target.containerId(),item,quantity)),List.of(new ExpectedRevision(source,gift.sourceRevision()),new ExpectedRevision(target.containerId(),gift.targetRevision())),"donation:"+id,now);
        var receipt=new TransferService(rights,TransferLedgerData.get(server)).submit(request,Map.of(source,new TransferEndpoint(source,inventory(donor)),target.containerId(),new TransferEndpoint(target.containerId(),box)),now);
        return finishDonation(server,gift,receipt,now);
    }
    private static DonationRecord finishDonation(MinecraftServer server,DonationRecord gift,TransferReceipt receipt,long now){
        boolean valid=receipt!=null&&receipt.transferId().equals(gift.id())&&receipt.completedAt()>=gift.createdAt()
                &&(!receipt.successful()||receipt.finalRevisions().getOrDefault(gift.source(),-1L)>gift.sourceRevision()&&receipt.finalRevisions().getOrDefault(gift.destination(),-1L)>gift.targetRevision());
        var next=gift.result(!valid?DonationRecord.Status.REVIEW:receipt.successful()?DonationRecord.Status.RECORDED:DonationRecord.Status.FAILED,now,!valid?"意图缺少匹配的转移回执，人工核对，不重放":receipt.reason());ReligionData.get(server).donation(next);
        if(valid&&receipt.successful())KnowledgeStateData.get(server).document(gift.donor(),gift.id(),new FactKey(gift.temple(),"religion.donation_receipt",gift.id().toString()),"自己的捐赠已收讫："+gift.quantity()+" "+gift.item().itemId()+"，用途="+gift.purpose(),PrecisionBand.EXACT,1,now,0,DisclosurePolicy.PRIVATE);
        return next;
    }
    public static AlmsCase request(MinecraftServer server,TempleRecord temple,LivingEntity requester,ItemFingerprint item,int quantity,String statement){
        if(!near(requester,temple))throw new IllegalStateException("relief request requires local presence");
        long now=server.overworld().getGameTime();UUID character=character(requester);
        var d=ReligionData.get(server);
        var outstanding=d.cases().stream().filter(c->c.temple().equals(temple.id())&&c.recipient().equals(character)&&c.item().equals(item)
                &&(!c.closed()||c.stage()==AlmsCase.Stage.REVIEW)).findFirst().orElse(null);
        if(outstanding!=null){if(outstanding.quantity()!=quantity||!outstanding.statement().equals(statement))throw new IllegalStateException("an unresolved request already exists with different terms");return outstanding;}
        UUID id=ContainerIds.named("alms-case:"+temple.id()+":"+character+":"+item+":"+(now/24000));var previous=d.findAid(id);
        if(previous!=null){if(!previous.item().equals(item)||previous.quantity()!=quantity||!previous.statement().equals(statement))throw new IllegalStateException("same-day request already recorded with different terms");return previous;}
        var value=new AlmsCase(id,temple.id(),character,item,quantity,statement,AlmsCase.Stage.REQUESTED,null,null,null,0,now,now,now,null,"当事人到场自述，等待有职权者审查");d.aid(value);
        KnowledgeStateData.get(server).selfReport(character,character,new FactKey(temple.id(),"religion.own_request",id.toString()),"自己的救济申请已提交，尚未批准",PrecisionBand.CATEGORY,1,now,0,DisclosurePolicy.PRIVATE);return value;
    }
    public static AlmsCase review(NpcBase clergy,AlmsCase c){
        var server=MinecraftApi.server(clergy);long now=clergy.level().getGameTime();var t=ReligionData.get(server).temple(c.temple());var applicant=body(server,c.recipient());
        if(!authorized(clergy,t.id(),OfficeAuthority.REVIEW_ALMS)||!near(clergy,t)||applicant==null||!near(applicant,t)||clergy.distanceToSqr(applicant)>36||!clergy.hasLineOfSight(applicant))throw new IllegalStateException("valid reviewing office and actual applicant presence required");
        if(c.stage()!=AlmsCase.Stage.REQUESTED)throw new IllegalStateException("case not awaiting review");
        // Evaluate declared purpose and the public per-case rule; no remote hunger/wealth scan.
        boolean permitted=c.quantity()<=t.portion()&&c.item().itemId().equals("minecraft:bread")&&c.item().componentsHash()==0;
        var reviewed=c.update(permitted?AlmsCase.Stage.APPROVED:AlmsCase.Stage.DENIED,clergy.getCharacterId(),now,now,null,permitted?"当面接收自述，按公开份额规则批准；不声称已查明家庭财富":"不符合公开食品/份额规则",true);ReligionData.get(server).aid(reviewed);return reviewed;
    }
    public static void replenish(MinecraftServer server,TempleRecord t,AlmsCase c,long now){
        var flows=SupplyFlowData.get(server);var needs=NeedRequestData.get(server);String purpose="alms:"+t.id()+":"+c.item();
        var active=needs.requests().stream().filter(r->r.key().requester().equals(t.id())&&r.key().purpose().equals("INSTITUTION:"+purpose)&&!r.status().terminal()&&r.status()!=NeedRequestStatus.DELIVERED&&r.key().deadline()>now).findFirst().orElse(null);
        int wanted=Math.max(1,c.quantity()+t.retained()-SupplyFlowService.count(SupplyFlowService.container(server,t.alms()),c.item()));
        if(active==null)SupplyFlowService.request(server,SubjectRef.institution(t.id()),purpose,t.alms(),c.item(),wanted,now,now+24000);
        else needs.openOrUpdate(active.key(),Math.max(wanted,active.requested()),active.maxAttempts(),now);
        flows.reveal(SubjectRef.institution(t.id()),t.alms().containerId());
    }
    public static void issuedEvidence(MinecraftServer server,NpcBase deacon,AlmsCase c,long now){
        var knowledge=KnowledgeStateData.get(server);var key=new FactKey(c.temple(),"religion.alms_event",c.id().toString());
        knowledge.document(c.recipient(),c.id(),key,"自己的救济已收到："+c.quantity()+" "+c.item().itemId(),PrecisionBand.EXACT,1,now,0,DisclosurePolicy.PRIVATE);
        knowledge.observe(deacon.getCharacterId(),key,"本人实际发放救济并登记回执",PrecisionBand.EXACT,1,"本人执行",now,0,DisclosurePolicy.PRIVATE);
        for(var viewer:server.getPlayerList().getPlayers())witness(viewer,deacon,c,now);
    }
    public static boolean witness(ServerPlayer viewer,NpcBase deacon,AlmsCase c,long now){
        UUID observer=currentCharacter(viewer);if(observer==null)return false;
        var server=MinecraftApi.server(deacon);var d=ReligionData.get(server);var authoritative=d.aid(c.id());var id=d.receipt(c.id(),false);var receipt=id==null?null:TransferLedgerData.get(server).receipt(id);
        var recipient=body(server,c.recipient());
        if(c.stage()!=AlmsCase.Stage.ISSUED||receipt==null||!receipt.successful()||now<receipt.completedAt()||now-receipt.completedAt()>40
                ||!authoritative.equals(c)||!deacon.getCharacterId().equals(c.workerCharacter())
                ||recipient==null||viewer.level()!=deacon.level()||viewer.distanceToSqr(deacon)>64||!viewer.hasLineOfSight(deacon)||!viewer.hasLineOfSight(recipient))return false;
        KnowledgeStateData.get(server).observe(observer,new FactKey(c.temple(),"religion.alms_event",c.id().toString()),"亲眼看到这座教堂交付一份救济",PrecisionBand.CATEGORY,.9,"当面见证",now,0,DisclosurePolicy.SHAREABLE);return true;
    }
    public static boolean interacting(LivingEntity e,net.minecraft.core.BlockPos pos){
        var center=net.minecraft.world.phys.Vec3.atCenterOf(pos);if(e.getEyePosition().distanceToSqr(center)>20.25)return false;
        var hit=e.level().clip(new net.minecraft.world.level.ClipContext(e.getEyePosition(),center,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,e));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||hit.getBlockPos().equals(pos);
    }
    public static List<String> knownRecords(MinecraftServer server,UUID knower,UUID temple){return KnowledgeStateData.get(server).beliefsAbout(knower,temple).stream().filter(b->b.key().predicate().startsWith("religion.")).map(b->b.displayValue()+"（来源："+b.sourceLabel()+"；记录时间="+b.lastConfirmedAt()+"；这是当时的记录，不是后台当前状态）").toList();}
}
