package com.jgeted.sagadyssey.social.settlement;
import com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData;
import com.jgeted.sagadyssey.social.character.CharacterRegistry;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.neoforged.bus.api.SubscribeEvent;
/** Managed settlement stores use explicit authority; ordinary membership is not a vanilla-menu bypass. */
public final class NaturalStorageAccessEvents {
 private static boolean denied(ServerLevel level,ServerPlayer player,BlockPos pos){
  var id=NaturalVillageLifeData.managedStore(level.getServer(),level.dimension().identifier().toString(),pos);if(id==null)return false;
  var principal=CharacterRegistry.currentId(player);if(!player.isAlive()||principal==null)return true;
  var actor=SubjectRef.character(principal);var rights=RightsStateData.get(level.getServer());var resolver=new RightsService(rights);long now=level.getGameTime();
  if(resolver.can(actor,AssetRight.MANAGE,id,-1,new ItemFingerprint("minecraft:carrot",0),now).status()!=RightsVerdict.Status.ALLOW)return true;
  if(level.getBlockEntity(pos) instanceof Container c)for(int i=0;i<c.getContainerSize();i++)if(!c.getItem(i).isEmpty()&&resolver.can(actor,AssetRight.TRANSFER,id,i,ItemFingerprint.of(c.getItem(i)),now).status()!=RightsVerdict.Status.ALLOW)return true;
  return false;
 }
 @SubscribeEvent public static void open(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e){if(e.getLevel() instanceof ServerLevel l&&e.getEntity() instanceof ServerPlayer p&&denied(l,p,e.getPos())){e.setCanceled(true);p.displayClientMessage(net.minecraft.network.chat.Component.literal("此仓储由持有者管理；成员可通过交付领取食物"),true);}}
 @SubscribeEvent public static void destroy(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e){if(e.getLevel() instanceof ServerLevel l&&e.getPlayer() instanceof ServerPlayer p&&denied(l,p,e.getPos()))e.setCanceled(true);}
}
