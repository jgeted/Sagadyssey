package com.jgeted.sagadyssey.social.industry;

import com.jgeted.sagadyssey.gametest.PortedGameTests.*;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;
import static com.jgeted.sagadyssey.social.industry.IndustryCompletionGameTests.*;
import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.social.industry.common.*;
import com.jgeted.sagadyssey.social.needs.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import java.util.*;
import net.neoforged.neoforge.gametest.*;

/** Explicit request withdrawal is an injected event, not a fabricated native outcome. */
@GameTestHolder("sagadyssey_industries_completion") @PrefixGameTestTemplate(false)
public final class NativeCancellationGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("sagadyssey.industry.native"))return List.of();
        return switch(System.getProperty("sagadyssey.completion.phase","disabled")){
            case "cancel-start"->List.of(new TestFunction("native_cancel","cancel.live_request_ends","sagadyssey_industries_completion:empty",5000,0,true,NativeCancellationGameTests::start));
            case "cancel-resume"->List.of(new TestFunction("native_cancel","cancel.reboot_original_source","sagadyssey_industries_completion:empty",6000,0,true,NativeCancellationGameTests::resume));
            default->List.of();
        };
    }
    private static void start(GameTestHelper h){
        floor(h,0);var l=h.getLevel();var s=l.getServer();long now=l.getGameTime();
        var owner=npc(h,4,6,NpcProfession.WORKER);family(h,"original bounded cancellation household",List.of(owner));bed(h,owner,4,7);
        owner.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.SHEARS));
        for(int x=2;x<=12;x++)for(int z=2;z<=12;z++){
            l.setBlockAndUpdate(at(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState());
            if(x==2||x==12||z==2||z==12){l.setBlockAndUpdate(at(x,0,z),Blocks.OAK_FENCE.defaultBlockState());l.setBlockAndUpdate(at(x,1,z),Blocks.OAK_FENCE.defaultBlockState());}
        }
        var actor=SubjectRef.character(owner.getCharacterId());var output=point(h,at(4,0,9),actor,TransferIntent.DELIVERY);SupplyFlowData.get(s).reveal(actor,output.containerId());
        var need=SupplyFlowService.request(s,actor,"native-cancel:original",output,fp("white_wool"),1,now,now+5000);
        var sheep=EntityType.SHEEP.create(l, net.minecraft.world.entity.EntitySpawnReason.COMMAND);sheep.setPos(at(6,0,6).getX()+.5,80,at(6,0,6).getZ()+.5);sheep.setColor(DyeColor.WHITE);sheep.setPersistenceRequired();l.addFreshEntity(sheep);
        var source=EntityResourceAuthority.registerInitialOwned(owner,sheep,"SHEAR",output,need.id(),at(2,0,2),at(12,3,12),"finite original owned sheep for cancellation validation");
        UUID[] pending={null};boolean[] withdrawn={false};sprint(h,5500);
        h.onEachTick(()->{
            var data=EntityResourceActionData.existing(s);if(data==null)return;
            if(!withdrawn[0]){
                var a=data.actions().stream().filter(x->x.read("Source", UUIDUtil.CODEC).orElseThrow().equals(source)&&x.getStringOr("State", "").equals("READY")).findFirst().orElse(null);if(a==null)return;
                h.assertTrue(!sheep.isSheared()&&!a.contains("ToolBefore")&&MinecraftApi.listOfType(a, "Drops",Tag.TAG_COMPOUND).isEmpty(),"target path reached before native effect");
                pending[0]=a.read("Id", UUIDUtil.CODEC).orElseThrow();
                NeedRequestData.get(s).transition(need.id(),NeedRequestStatus.CANCELLED,l.getGameTime(),"TEST INJECTION: original consumer withdraws before native effect");withdrawn[0]=true;
                Sagadyssey.LOGGER.info("NATIVE_CANCEL_WITHDRAW tick={} action={} state=READY injection=explicit-request-withdrawal",l.getGameTime(),pending[0]);return;
            }
            var a=data.action(pending[0]);if(!a.getStringOr("State", "").equals("CANCELLED"))return;
            h.assertTrue(!sheep.isSheared()&&owner.getMainHandItem().getDamageValue()==0&&count(box(h,output),Items.WHITE_WOOL)==0,"cancelled action neither shears nor changes material/tool");
            var before=a.copy();h.assertTrue(data.cancelUnstarted(pending[0],NeedRequestData.get(s).request(need.id()),l.getGameTime(),"repeat"),"repeat cancellation confirms original");h.assertTrue(before.equals(data.action(pending[0])),"repeat does not rewrite original cancellation");
            var mark=new CompoundTag();actor(mark,"Owner",owner);mark.store("Sheep", UUIDUtil.CODEC,sheep.getUUID());mark.store("Source", UUIDUtil.CODEC,source);mark.store("Need", UUIDUtil.CODEC,need.id());mark.store("CancelledAction", UUIDUtil.CODEC,pending[0]);mark.put("Output",output.save());save(h,mark);
            Sagadyssey.LOGGER.info("NATIVE_CANCEL_CHECKPOINT tick={} person={} action={} nativeEffects=0 wool=0 toolDamage=0 schema=2",l.getGameTime(),owner.getCharacterId(),pending[0]);h.succeed();
        });
    }
    private static void resume(GameTestHelper h){
        if(prior==null)throw new IllegalStateException("complete original cancellation world required");var l=h.getLevel();var s=l.getServer();var owner=original(h,"Owner");var data=EntityResourceActionData.existing(s);var old=data.action(prior.read("CancelledAction", UUIDUtil.CODEC).orElseThrow());
        h.assertTrue(old!=null&&old.getStringOr("State", "").equals("CANCELLED"),"same persisted cancellation after full process restart");
        h.assertTrue(l.getEntity(prior.read("Sheep", UUIDUtil.CODEC).orElseThrow()) instanceof Sheep sheep&&!sheep.isSheared(),"same original unshorn sheep");
        var output=FieldworkStorage.load(prior.getCompoundOrEmpty("Output"));var next=SupplyFlowService.request(s,SubjectRef.character(owner.getCharacterId()),"native-cancel:follow-up",output,fp("white_wool"),1,l.getGameTime(),l.getGameTime()+6000);sprint(h,6500);
        h.succeedWhen(()->{
            var actions=data.actions();h.assertTrue(actions.stream().noneMatch(x->x.getStringOr("State", "").equals("REVIEW")),"no uncertain native result");
            var real=actions.stream().filter(x->x.getStringOr("State", "").equals("DONE")).toList();h.assertTrue(real.size()==1&&real.getFirst().read("Need", UUIDUtil.CODEC).orElseThrow().equals(next.id()),"new actual need executes exactly one native action");
            h.assertTrue(data.action(prior.read("CancelledAction", UUIDUtil.CODEC).orElseThrow()).equals(old),"original cancelled identity/facts not rewritten");
            h.assertTrue(actions.stream().filter(x->x.getStringOr("State", "").equals("CANCELLED")).count()==1,"cancellation counted separately from actual work");
            int produced=MinecraftApi.listOfType(real.getFirst(), "Drops",Tag.TAG_COMPOUND).stream().map(x->(CompoundTag)x).mapToInt(x->x.getIntOr("Quantity", 0)).sum();h.assertTrue(count(box(h,output),Items.WHITE_WOOL)==produced&&produced>=1,"actual native wool physically deposited once");
            h.assertTrue(owner.getMainHandItem().is(Items.SHEARS)&&owner.getMainHandItem().getDamageValue()==1,"same original tool wears once for actual work only");
            Sagadyssey.LOGGER.info("NATIVE_CANCEL_RESUME_ACCEPT tick={} person={} originalSheep={} cancelled=1 actualDONE=1 produced={} finalOriginalNeed=CANCELLED",l.getGameTime(),owner.getCharacterId(),prior.read("Sheep", UUIDUtil.CODEC).orElseThrow(),produced);save(h,prior.copy());
        });
    }
}
