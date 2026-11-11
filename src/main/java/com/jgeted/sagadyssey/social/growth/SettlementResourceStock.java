package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.core.structure.*;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.household.HouseholdData;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Read-only, destination-scoped reserve projection. Incoming promises are never edible/spendable stock. */
public final class SettlementResourceStock {
    private SettlementResourceStock(){}
    private record Cache(long tick,List<ResourceReserveRules.Rule> rules,Map<String,Integer> stock){}
    private static final Map<NpcBase,Cache> CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    public static boolean applies(NpcBase n){
        if(!Boolean.parseBoolean(System.getProperty("sagadyssey.resource.reserves","true"))||ResourceReserveRules.rules().isEmpty())return false;
        var home=HouseholdData.get(MinecraftApi.server(n)).forMember(n.getCharacterId());
        return home!=null&&home.settlementId()!=null&&home.sharedStorage()!=null;
    }
    public static NaturalVillageBuilding village(NpcBase n){
        var b=SettlementDevelopmentData.residentVillage(MinecraftApi.server(n),n.getCharacterId());
        return b==null?NaturalFarmRules.forResident(n):b;
    }
    public static int living(NpcBase n){
        var home=HouseholdData.get(MinecraftApi.server(n)).forMember(n.getCharacterId());if(home==null)return 0;
        var chars=CharacterRegistryData.get(MinecraftApi.server(n));
        return (int)home.members().stream().filter(m->{var p=chars.byId(m.characterId());return p!=null&&p.alive();}).count();
    }
    public static int target(NpcBase n,ResourceReserveRules.Rule rule){return rule.target(living(n));}
    public static int available(NpcBase n,ResourceReserveRules.Rule rule){
        var rules=ResourceReserveRules.rules();long now=n.level().getGameTime();var cached=CACHE.get(n);
        if(cached!=null&&cached.tick==now&&cached.rules==rules)return cached.stock.getOrDefault(rule.id().toString(),0);
        var counts=new HashMap<String,Integer>();var data=FieldworkData.get(MinecraftApi.server(n));
        var destination=FieldworkDepositTaskExecutor.storageFor(n,data);
        if(destination!=null&&destination.dimension().equals(n.level().dimension().identifier().toString())&&n.level().hasChunkAt(destination.position())){
            var box=com.jgeted.sagadyssey.social.logistics.WarehouseContainers.asContainer(n.level().getBlockEntity(destination.position()));
            if(box!=null){var rights=RightsStateData.get(MinecraftApi.server(n));var service=new RightsService(rights);
                var byFingerprint=new HashMap<ItemFingerprint,Integer>();var samples=new HashMap<ItemFingerprint,ItemStack>();
                for(int slot=0;slot<box.getContainerSize();slot++){var stack=box.getItem(slot);if(stack.isEmpty())continue;var fp=ItemFingerprint.of(stack);
                    if(service.can(destination.owner(),AssetRight.TRANSFER,destination.containerId(),slot,fp,now).status()!=RightsVerdict.Status.ALLOW)continue;
                    byFingerprint.merge(fp,stack.getCount(),Integer::sum);samples.put(fp,stack);}
                byFingerprint.forEach((fp,q)->{int free=Math.max(0,q-rights.reservedQuantity(destination.containerId(),fp,null,now));
                    for(var r:rules)if(samples.get(fp).is(r.stock())&&(!r.stock().equals(SettlementMaterials.WALL_ITEMS)||SettlementMaterials.masonry(samples.get(fp))))counts.merge(r.id().toString(),free,Integer::sum);});
            }
        }
        CACHE.put(n,new Cache(now,rules,Map.copyOf(counts)));return counts.getOrDefault(rule.id().toString(),0);
    }
    public static boolean needsAny(NpcBase n){return ResourceReserveRules.rules().stream().anyMatch(r->available(n,r)<target(n,r));}
    private static boolean exactNeed(NpcBase n,BlockState state){
        var home=HouseholdData.get(MinecraftApi.server(n)).forMember(n.getCharacterId());
        var sample=new ItemStack(state.getBlock().asItem());if(sample.isEmpty()||home==null)return false;
        return com.jgeted.sagadyssey.social.needs.NeedRequestData.get(MinecraftApi.server(n)).requests().stream()
                .anyMatch(r->!r.status().terminal()&&r.remaining()>0&&r.key().item().matches(sample)
                        &&(r.key().requester().equals(home.id())||r.key().requester().equals(n.getCharacterId())||r.key().requester().equals(home.settlementId())));
    }
    public static boolean wants(NpcBase n,BlockState state){
        if(!applies(n))return true;
        if(exactNeed(n,state))return true;
        var rule=ResourceReserveRules.rules().stream().filter(r->state.is(r.sources())).findFirst().orElse(null);
        if(rule==null&&state.is(SettlementMaterials.COVER))return needsAny(n);
        if(rule==null)return state.is(net.minecraft.tags.BlockTags.LEAVES)&&ResourceReserveRules.rules().stream()
                .filter(r->r.id().getPath().equals("timber")).anyMatch(r->available(n,r)<target(n,r));
        if(available(n,rule)<target(n,rule))return true;
        var b=village(n);
        // Real remaining engineering demand may exceed the maintenance buffer.
        return b!=null&&SettlementMaterials.quarry(state)&&SettlementDevelopmentData.materialDeficit(MinecraftApi.server(n),b)>0;
    }
}
