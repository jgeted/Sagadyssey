package com.jgeted.sagadyssey.npc.brain.command;
import com.jgeted.sagadyssey.npc.brain.execution.*;
import com.jgeted.sagadyssey.npc.brain.persistence.NpcBehaviorMode;
import com.jgeted.sagadyssey.npc.entity.*;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import com.jgeted.sagadyssey.npc.registry.NpcEntityTypes;
import com.jgeted.sagadyssey.social.combat.*;
import com.jgeted.sagadyssey.social.life.NpcLifeData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("sagadyssey") @PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class NpcDutyRestartGameTests {
    private static CompoundTag previous;
    @net.neoforged.bus.api.SubscribeEvent public static void readCheckpoint(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!(e.getServer() instanceof GameTestServer))return;previous=null;if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart"))return;
        try{previous=NbtIo.readCompressed(e.getServer().getWorldPath(LevelResource.ROOT).resolve("phase9-duty-checkpoint.nbt"),NbtAccounter.unlimitedHeap());
            e.getServer().getWorldData().overworldData().setGameTime(Math.max(e.getServer().overworld().getGameTime(),previous.getLong("Time")+1));}
        catch(Exception ex){throw new IllegalStateException("mandatory original medical duty checkpoint",ex);}}
    @GameTest(template="fieldwork_empty",batch="phase9_medical_checkpoint",timeoutTicks=2000)
    public static void saveOriginalDoctorPatientConsentAndPartialWork(GameTestHelper h){if(Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.assertTrue(previous!=null,"restart retains mandatory baseline checkpoint, never creates substitute patients");h.succeed();return;}var pos=h.absolutePos(new BlockPos(19000,1,19000));force(h.getLevel(),pos);
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)h.getLevel().setBlock(pos.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);
        NpcBase[] actors=new NpcBase[2];NpcDutyData.Duty[] original={null};
        h.succeedWhen(()->{
            if(actors[0]==null){h.assertTrue(h.getLevel().isPositionEntityTicking(pos)&&h.getLevel().isPositionEntityTicking(pos.offset(1,0,0)),"both original checkpoint chunks ready for real entity ticking before spawn");actors[0]=create(h.getLevel(),pos,NpcProfession.MEDIC);actors[1]=create(h.getLevel(),pos.offset(1,0,0),NpcProfession.WARRIOR);actors[1].setNoAi(true);original[0]=NpcDutyGameTests.medical(actors[0],actors[1],24000);}
            var doctor=actors[0];var patient=actors[1];var d=NpcDutyData.get(doctor.getServer()).find(original[0].id());h.assertTrue(d.worked()>0&&d.worked()<28,"actual partial treatment before save, not a seeded counter: duty="+d+" ticks="+doctor.tickCount+" position="+doctor.position()+" plan="+NpcBrainRuntime.data(doctor.getServer()).active(doctor.getUUID()));doctor.setNoAi(true);
            var t=new CompoundTag();t.putUUID("Doctor",doctor.getUUID());t.putUUID("Patient",patient.getUUID());t.putUUID("Duty",d.id());t.putUUID("Wound",d.source());t.putInt("Worked",d.worked());t.putLong("Position",pos.asLong());t.putLong("Time",h.getLevel().getGameTime());
            try{NbtIo.writeCompressed(t,doctor.getServer().getWorldPath(LevelResource.ROOT).resolve("phase9-duty-checkpoint.nbt"));doctor.getServer().saveEverything(true,true,true);net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();}
            catch(Exception ex){throw new IllegalStateException("save original partial medical duty",ex);}});}
    @GameTest(template="fieldwork_empty",batch="phase9_medical_restart",timeoutTicks=2000)
    public static void newProcessResumesSameMedicalConsentAndBodiesOnlyOnce(GameTestHelper h){if(!Boolean.getBoolean("sagadyssey.phase4.requireRestart")){h.succeed();return;}h.assertTrue(previous!=null,"original duty checkpoint required");force(h.getLevel(),BlockPos.of(previous.getLong("Position")));boolean[] resumed={false};
        h.succeedWhen(()->{var doctor=(NpcBase)h.getLevel().getEntity(previous.getUUID("Doctor"));var patient=(NpcBase)h.getLevel().getEntity(previous.getUUID("Patient"));h.assertTrue(doctor!=null&&patient!=null,"original persisted doctor and patient, no clones");
            var d=NpcDutyData.get(doctor.getServer()).find(previous.getUUID("Duty"));h.assertTrue(d!=null&&d.source().equals(previous.getUUID("Wound"))&&d.targetBody().equals(patient.getUUID()),"same wound and protected target identity");
            if(!resumed[0]){h.assertTrue(d.worked()==previous.getInt("Worked")&&doctor.getPersonalInventory().count(s->s.is(Items.WHITE_WOOL))==1,"offline time did not treat or consume supplies");h.assertTrue(NpcDutyEvidenceData.get(doctor.getServer()).accepts(d,h.getLevel().getGameTime()),"original patient consent survived new JVM");doctor.setNoAi(false);resumed[0]=true;}
            h.assertTrue(NpcDutyData.get(doctor.getServer()).find(d.id()).status()==NpcDutyData.Status.COMPLETED,"same duty completed after restart");h.assertTrue(InjuryData.get(doctor.getServer()).treatments().stream().filter(t->t.woundId().equals(d.source())).count()==1&&doctor.getPersonalInventory().count(s->s.is(Items.WHITE_WOOL))==0,"one actual dressing and one treatment receipt across processes");});}
    private static NpcBase create(ServerLevel l,BlockPos pos,NpcProfession role){var n=new NpcBase(NpcEntityTypes.NPC_BASE.get(),l);n.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);n.setProfession(role);n.getPersonalInventory().clearContent();n.setPersistenceRequired();n.setCommand(NpcCommand.IDLE);l.addFreshEntity(n);NpcLifeData.get(l.getServer()).ensureRegistered(n,l.getGameTime());NpcBrainRuntime.setMode(n,NpcBehaviorMode.PLANNED);return n;}
    private static void force(ServerLevel l,BlockPos pos){for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){l.setChunkForced((pos.getX()>>4)+x,(pos.getZ()>>4)+z,true);l.getChunk((pos.getX()>>4)+x,(pos.getZ()>>4)+z);}}
}
