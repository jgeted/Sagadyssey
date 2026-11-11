package com.jgeted.sagadyssey.core.structure;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.social.needs.SupplyFlowData;
import com.jgeted.sagadyssey.worldsim.lod.NaturalVillageEconomyData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import java.util.*;

/** Read-only observation of the unchanged legacy test. Native sprint changes wall time only. */
@net.neoforged.fml.common.EventBusSubscriber(modid="sagadyssey")
public final class LodP8DiagnosticGameTests {
    private static final Set<MinecraftServer> started=Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean enabled(){return System.getProperty("sagadyssey.society.phase","").equals("r2-cargo")||System.getProperty("sagadyssey.society.phase","").startsWith("p8-integrated");}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        if(!enabled()||!(e.getServer() instanceof net.minecraft.gametest.framework.GameTestServer))return;
        var s=e.getServer();if(System.getProperty("sagadyssey.society.phase","").equals("r2-cargo")&&s.getTickCount()>40&&started.add(s))s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),"tick rate 200");
        if(s.getTickCount()%500!=0)return;var d=NaturalVillageEconomyData.existing(s);if(d==null)return;
        for(var r:d.rows()){var b=NaturalVillageLifeData.decode(r.getCompoundOrEmpty("Village"));var l=s.overworld();
            var bodies=new ArrayList<String>();for(int i=0;i<3;i++){var n=NaturalVillageR1GameTests.actor(l,b,i);bodies.add(n==null?"ABSENT":n.getUUID()+"@"+n.blockPosition()+" NoAI="+n.isNoAi());}
            Sagadyssey.LOGGER.info("P8_OLD_DIAG tick={} village={} origin={} rotation={} phase={} reason={} forced={} bodies={} routes={} tickets={}",s.getTickCount(),b.buildingKey(),b.buildingOrigin(),b.rotation(),r.getStringOr("Phase", ""),r.getStringOr("Reason", "")+" flags="+r.getBooleanOr("ObservedAway", false)+"/"+r.getBooleanOr("ReturnRequested", false)+" retained="+NaturalVillageLayout.stores(b).stream().map(p->NaturalVillageLayout.at(b,p)).map(p->p+":"+(((NaturalVillageEconomyData.RetainedChunks)(Object)l.getChunkSource().chunkMap).sagadyssey$retained(new ChunkPos(p).toLong())!=null)).toList(),l.getForceLoadedChunks(),bodies,SupplyFlowData.get(s).routes(),tickets(l,b));
        }
    }
    private static List<String> tickets(ServerLevel l,NaturalVillageBuilding b){
        var out=new ArrayList<String>();try{
            Object manager=l.getChunkSource().chunkMap.getDistanceManager();
            for(Class<?> type=manager.getClass();type!=null;type=type.getSuperclass())for(var f:type.getDeclaredFields())if(f.getName().equals("tickets")){
                f.setAccessible(true);var map=(it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>)f.get(manager);
                for(var entry:map.long2ObjectEntrySet()){var cp=new ChunkPos(entry.getLongKey());if(Math.abs(cp.x-(b.buildingOrigin().getX()>>4))<=12&&Math.abs(cp.z-(b.buildingOrigin().getZ()>>4))<=12)out.add(cp+":"+entry.getValue());}
            }
        }catch(Exception ex){out.add("DIAGNOSTIC_ERROR:"+ex);}
        return out;
    }
    @SubscribeEvent public static void leave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e){if(enabled()&&e.getEntity() instanceof com.jgeted.sagadyssey.npc.entity.NpcBase n)Sagadyssey.LOGGER.info("P8_OLD_BODY_LEAVE body={} person={} reason={}",n.getUUID(),n.getCharacterId(),n.getRemovalReason());}
}
