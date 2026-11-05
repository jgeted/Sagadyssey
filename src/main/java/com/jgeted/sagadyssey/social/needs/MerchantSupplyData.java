package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.*;

/** Consenting owner quotes and merchant-local learned surplus/shortage. Emeralds remain physical. */
public final class MerchantSupplyData extends SavedData {
    public static final String NAME="sagadyssey_merchant_supply_v3";
    public static final Factory<MerchantSupplyData> FACTORY=new Factory<>(MerchantSupplyData::new,MerchantSupplyData::load);
    public record Offer(UUID id,UUID point,UUID settlement,ItemFingerprint item,boolean sells,
                        int unitEmeralds,int limit,long expiresAt) {
        public Offer {Objects.requireNonNull(id);Objects.requireNonNull(point);Objects.requireNonNull(settlement);Objects.requireNonNull(item);
            if(unitEmeralds<1||unitEmeralds>64||limit<1||limit>64||expiresAt<0)throw new IllegalArgumentException("invalid real market quote");}
    }
    public record Observation(UUID offer,int available,long time){public Observation{Objects.requireNonNull(offer);if(available<0||available>64||time<0)throw new IllegalArgumentException("invalid supply observation");}}
    public enum Stage{BUY,SELL,DONE,REVIEW}
    public record Journey(UUID id,UUID merchantEntity,UUID merchantCharacter,UUID buy,UUID sell,int quantity,
                          Stage stage,UUID transfer,long personalRevision,long cargoRevision,long marketRevision,long time,long retryAt,String detail){
        public Journey{for(UUID value:List.of(id,merchantEntity,merchantCharacter,buy,sell))Objects.requireNonNull(value);Objects.requireNonNull(stage);
            if(buy.equals(sell)||quantity<1||quantity>64||personalRevision<0||cargoRevision<0||marketRevision<0||time<0||retryAt<0||detail==null||detail.length()>2048)throw new IllegalArgumentException("invalid merchant journey");}
        public String token(){return "merchant-route:"+id;}
        public Journey update(Stage next,UUID transaction,long personal,long cargo,long market,long now,long retry,String reason){return new Journey(id,merchantEntity,merchantCharacter,buy,sell,quantity,next,transaction,personal,cargo,market,now,retry,reason);}
    }
    private final Map<UUID,Offer> offers=new LinkedHashMap<>();
    private final Map<UUID,Map<UUID,Observation>> knowledge=new HashMap<>();
    private final Map<UUID,Journey> journeys=new LinkedHashMap<>();
    private final Map<UUID,UUID> buys=new HashMap<>(),sales=new HashMap<>();
    public void settlement(UUID journey,boolean buy,UUID receipt){var journal=buy?buys:sales;var old=journal.putIfAbsent(journey,receipt);if(old!=null&&!old.equals(receipt))throw new IllegalStateException("merchant settlement conflict");setDirty();}
    public UUID settlement(UUID journey,boolean buy){return (buy?buys:sales).get(journey);}
    public static MerchantSupplyData get(MinecraftServer server){var storage=server.overworld().getDataStorage();var d=storage.get(FACTORY,NAME);if(d!=null)return d;
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat")))throw new IllegalStateException("merchant ledger unreadable; recovery required");d=new MerchantSupplyData();storage.set(NAME,d);return d;}
    /** Caller must be the owner or an authorized administrative quote issuer; normal knowledge reveal is separate. */
    public void offer(MinecraftServer server,SubjectRef issuer,Offer quote){
        var point=SupplyFlowData.get(server).point(quote.point());
        if(!issuer.equals(point.owner())||!SupplyFlowService.validOwner(server,point))throw new IllegalArgumentException("quote issuer lacks ownership");
        var existing=offers.get(quote.id());if(existing!=null&&!existing.equals(quote))throw new IllegalStateException("quote identity conflict");
        if(existing==null&&offers.size()>=4096)throw new IllegalStateException("quote ledger full");offers.put(quote.id(),quote);setDirty();
    }
    /** Observations require actual local presence. A test/admin may explicitly communicate an existing quote. */
    public void observe(NpcBase merchant,UUID id,long now){var offer=offer(id);var point=SupplyFlowData.get(merchant.getServer()).point(offer.point());
        if(!point.dimension().equals(merchant.level().dimension().location().toString())||!FieldworkRoutes.closeEnough(merchant,point.position()))throw new IllegalStateException("remote quote cannot be observed");
        var inventory=SupplyFlowService.container(merchant.getServer(),point);if(inventory==null)throw new IllegalStateException("market unavailable");
        int available=offer.sells()?SupplyFlowService.count(inventory,offer.item()):SupplyFlowService.count(inventory,new ItemFingerprint("minecraft:emerald",0))/offer.unitEmeralds();
        reveal(merchant.getCharacterId(),new Observation(id,Math.min(offer.limit(),available),now));
    }
    public void reveal(UUID merchant,Observation observation){offer(observation.offer());var known=knowledge.computeIfAbsent(merchant,k->new HashMap<>());
        if(!known.containsKey(observation.offer())&&known.size()>=128)throw new IllegalStateException("merchant knowledge full");known.put(observation.offer(),observation);setDirty();}
    public Offer offer(UUID id){var value=offers.get(id);if(value==null)throw new NoSuchElementException("unknown owner quote");return value;}
    public List<Observation> known(UUID merchant){return List.copyOf(knowledge.getOrDefault(merchant,Map.of()).values());}
    public List<Journey> journeys(){return List.copyOf(journeys.values());}
    public Journey journey(UUID id){var value=journeys.get(id);if(value==null)throw new NoSuchElementException("unknown merchant journey");return value;}
    public void journey(Journey journey){var old=journeys.get(journey.id());
        if(old!=null&&(!old.merchantEntity().equals(journey.merchantEntity())||!old.merchantCharacter().equals(journey.merchantCharacter())
                ||!old.buy().equals(journey.buy())||!old.sell().equals(journey.sell())||old.quantity()!=journey.quantity()))
            throw new IllegalStateException("merchant journey identity changed");
        if(old==null&&journeys.size()>=4096)throw new IllegalStateException("merchant journeys full");journeys.put(journey.id(),journey);setDirty();}
    public int realizedProfit(UUID character){int total=0;for(var j:journeys.values())if(j.merchantCharacter().equals(character)&&j.stage()==Stage.DONE)total=Math.addExact(total,Math.multiplyExact(j.quantity(),offer(j.sell()).unitEmeralds()-offer(j.buy()).unitEmeralds()));return total;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider lookup){tag.putInt("Schema",1);var list=new ListTag();offers.values().forEach(o->{var t=new CompoundTag();t.putUUID("Id",o.id());t.putUUID("Point",o.point());t.putUUID("Settlement",o.settlement());t.put("Item",o.item().save());t.putBoolean("Sells",o.sells());t.putInt("Price",o.unitEmeralds());t.putInt("Limit",o.limit());t.putLong("Expires",o.expiresAt());list.add(t);});tag.put("Offers",list);
        var observations=new ListTag();knowledge.forEach((actor,known)->known.values().forEach(o->{var t=new CompoundTag();t.putUUID("Merchant",actor);t.putUUID("Offer",o.offer());t.putInt("Available",o.available());t.putLong("Time",o.time());observations.add(t);}));tag.put("Known",observations);
        var routes=new ListTag();journeys.values().forEach(j->{var t=new CompoundTag();t.putUUID("Id",j.id());t.putUUID("Entity",j.merchantEntity());t.putUUID("Character",j.merchantCharacter());t.putUUID("Buy",j.buy());t.putUUID("Sell",j.sell());t.putInt("Quantity",j.quantity());t.putString("Stage",j.stage().name());if(j.transfer()!=null)t.putUUID("Transfer",j.transfer());if(buys.containsKey(j.id()))t.putUUID("BuyReceipt",buys.get(j.id()));if(sales.containsKey(j.id()))t.putUUID("SaleReceipt",sales.get(j.id()));t.putLong("PersonalRevision",j.personalRevision());t.putLong("CargoRevision",j.cargoRevision());t.putLong("MarketRevision",j.marketRevision());t.putLong("Time",j.time());t.putLong("RetryAt",j.retryAt());t.putString("Detail",j.detail());routes.add(t);});tag.put("Journeys",routes);return tag;}
    public static MerchantSupplyData load(CompoundTag tag,HolderLookup.Provider lookup){if(!tag.contains("Schema",Tag.TAG_INT)||tag.getInt("Schema")!=1)throw new IllegalArgumentException("merchant schema");
        for(String name:List.of("Offers","Known","Journeys"))if(!(tag.get(name) instanceof ListTag list)||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND||list.size()>524288)throw new IllegalArgumentException("invalid merchant list");var d=new MerchantSupplyData();
        for(Tag value:tag.getList("Offers",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;for(String key:List.of("Price","Limit"))require(t,key,Tag.TAG_INT);require(t,"Expires",Tag.TAG_LONG);require(t,"Sells",Tag.TAG_BYTE);var item=t.getCompound("Item");require(item,"Item",Tag.TAG_STRING);require(item,"Components",Tag.TAG_INT);
            var o=new Offer(uuid(t,"Id"),uuid(t,"Point"),uuid(t,"Settlement"),ItemFingerprint.load(item),t.getBoolean("Sells"),t.getInt("Price"),t.getInt("Limit"),t.getLong("Expires"));if(d.offers.size()>=4096||d.offers.putIfAbsent(o.id(),o)!=null)throw new IllegalArgumentException("duplicate quote");}
        for(Tag value:tag.getList("Known",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;require(t,"Available",Tag.TAG_INT);require(t,"Time",Tag.TAG_LONG);UUID actor=uuid(t,"Merchant"),offer=uuid(t,"Offer");if(d.knowledge.getOrDefault(actor,Map.of()).containsKey(offer))throw new IllegalArgumentException("duplicate observation");d.reveal(actor,new Observation(offer,t.getInt("Available"),t.getLong("Time")));}
        for(Tag value:tag.getList("Journeys",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;require(t,"Quantity",Tag.TAG_INT);require(t,"Detail",Tag.TAG_STRING);for(String key:List.of("PersonalRevision","CargoRevision","MarketRevision","Time","RetryAt"))require(t,key,Tag.TAG_LONG);
            var j=new Journey(uuid(t,"Id"),uuid(t,"Entity"),uuid(t,"Character"),uuid(t,"Buy"),uuid(t,"Sell"),t.getInt("Quantity"),Stage.valueOf(t.getString("Stage")),t.contains("Transfer")?uuid(t,"Transfer"):null,t.getLong("PersonalRevision"),t.getLong("CargoRevision"),t.getLong("MarketRevision"),t.getLong("Time"),t.getLong("RetryAt"),t.getString("Detail"));d.offer(j.buy());d.offer(j.sell());if(d.journeys.containsKey(j.id()))throw new IllegalArgumentException("duplicate merchant journey");d.journey(j);
            if(t.contains("BuyReceipt"))d.settlement(j.id(),true,uuid(t,"BuyReceipt"));if(t.contains("SaleReceipt"))d.settlement(j.id(),false,uuid(t,"SaleReceipt"));
            if((j.stage()==Stage.SELL||j.stage()==Stage.DONE)&&!d.buys.containsKey(j.id())||j.stage()==Stage.DONE&&!d.sales.containsKey(j.id()))throw new IllegalArgumentException("unproven merchant settlement");}
        return d;}
    private static void require(CompoundTag t,String key,int type){if(!t.contains(key,type))throw new IllegalArgumentException("missing merchant field "+key);}
    private static UUID uuid(CompoundTag t,String key){if(!t.hasUUID(key))throw new IllegalArgumentException("missing merchant UUID "+key);return t.getUUID(key);}
}
