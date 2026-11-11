package com.jgeted.sagadyssey.social.industry.workshop;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkStorage;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.needs.SupplyFlowService;
import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/** Three existing wood operations, with concrete materials bound by the loaded native recipe.
 * Tags select candidates; neither tags nor recipes confer rights or create inventory. */
public final class WoodMaterialRecipes {
    private WoodMaterialRecipes() {}
    public record Spec(String kind,List<ProductionMaterial> inputs,ProductionMaterial output,int work) {}
    private static ItemStack sample(ProductionMaterial m) {
        if(m.fingerprint().componentsHash()!=0)return ItemStack.EMPTY;
        var key=Identifier.tryParse(m.fingerprint().itemId());
        return key==null||!BuiltInRegistries.ITEM.containsKey(key)?ItemStack.EMPTY:new ItemStack(BuiltInRegistries.ITEM.getValue(key));
    }
    public static int outputQuantity(ItemFingerprint output) {
        var s=sample(new ProductionMaterial(output,1));
        return s.is(ItemTags.PLANKS)?12:s.is(Items.STICK)?4:s.is(ItemTags.BEDS)?1:0;
    }
    public static boolean shape(String kind,List<ProductionMaterial> in,ProductionMaterial out,int work) {
        if(out.fingerprint().componentsHash()!=0||in.stream().anyMatch(m->m.fingerprint().componentsHash()!=0))return false;
        return switch(kind) {
            case WorkshopTask.PLANKS -> work==200&&in.size()==1&&in.getFirst().quantity()==3&&out.quantity()==12;
            case WorkshopTask.STICKS -> work==200&&in.size()==1&&in.getFirst().quantity()==2&&out.quantity()==4;
            case WorkshopTask.BED -> work==400&&in.size()==2&&in.stream().allMatch(m->m.quantity()==3)&&out.quantity()==1;
            default -> false;
        };
    }
    public static boolean matches(MinecraftServer server,ProductionOrder order) {
        return shape(order.recipeId(),order.inputs(),order.output(),order.requiredWorkTicks())
                && nativeOutput(server,new Spec(order.recipeId(),order.inputs(),order.output(),order.requiredWorkTicks()));
    }
    private static boolean nativeOutput(MinecraftServer server,Spec spec) {
        var inputs=spec.inputs();List<ItemStack> grid;int width,height,multiplier=1;
        if(spec.kind().equals(WorkshopTask.PLANKS)) {
            var log=sample(inputs.getFirst());if(!log.is(ItemTags.LOGS)||!sample(spec.output()).is(ItemTags.PLANKS))return false;
            width=height=1;grid=List.of(log);multiplier=3;
        } else if(spec.kind().equals(WorkshopTask.STICKS)) {
            var plank=sample(inputs.getFirst());if(!plank.is(ItemTags.PLANKS)||!sample(spec.output()).is(Items.STICK))return false;
            width=1;height=2;grid=List.of(plank,plank.copy());
        } else {
            var plank=sample(inputs.get(0));var wool=sample(inputs.get(1));
            if(!plank.is(ItemTags.PLANKS)||!wool.is(ItemTags.WOOL)||!sample(spec.output()).is(ItemTags.BEDS))return false;
            width=3;height=2;grid=List.of(wool,wool.copy(),wool.copy(),plank,plank.copy(),plank.copy());
        }
        var input=CraftingInput.of(width,height,grid);
        var recipe=server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,server.overworld()).orElse(null);
        if(recipe==null||recipe.value().getRemainingItems(input).stream().anyMatch(s->!s.isEmpty()))return false;
        var output=recipe.value().assemble(input,server.registryAccess());
        return spec.output().fingerprint().matches(output)&&output.getCount()*multiplier==spec.output().quantity();
    }
    private static ProductionMaterial material(ItemStack s,int quantity) {return new ProductionMaterial(ItemFingerprint.of(s),quantity);}
    /** A concrete existing three-log batch; discovery creates neither boards nor claims. */
    private static Spec lawfulPlanksFromLog(NpcBase owner,FieldworkStorage stock,ItemStack log,long now) {
        if(!log.is(ItemTags.LOGS)||!log.getComponentsPatch().isEmpty()
                ||SupplyFlowService.available(MinecraftApi.server(owner),stock,SubjectRef.character(owner.getCharacterId()),ItemFingerprint.of(log),now)<3)return null;
        var input=CraftingInput.of(1,1,List.of(log));
        var recipe=MinecraftApi.server(owner).getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,MinecraftApi.server(owner).overworld()).orElse(null);
        if(recipe==null||recipe.value().getRemainingItems(input).stream().anyMatch(item->!item.isEmpty()))return null;
        var output=recipe.value().assemble(input,MinecraftApi.server(owner).registryAccess());
        if(!output.is(ItemTags.PLANKS)||output.getCount()!=4||!output.getComponentsPatch().isEmpty())return null;
        var spec=new Spec(WorkshopTask.PLANKS,List.of(material(log,3)),new ProductionMaterial(ItemFingerprint.of(output),12),200);
        return nativeOutput(MinecraftApi.server(owner),spec)?spec:null;
    }
    /** Read-only, two-edge native material plan. Forecasts never become inventory or rights. */
    public static List<Spec> upstream(NpcBase owner,FieldworkStorage stock,ItemFingerprint requested,int required,long now){
        if(required<1)return List.of();
        var principal=SubjectRef.character(owner.getCharacterId());
        java.util.function.ToIntFunction<ItemFingerprint> free=fp->SupplyFlowService.available(MinecraftApi.server(owner),stock,principal,fp,now);
        if(free.applyAsInt(requested)>=required)return List.of();
        var sample=sample(new ProductionMaterial(requested,1));
        boolean rod=sample.is(Items.FISHING_ROD), bed=sample.is(ItemTags.BEDS), sticks=sample.is(Items.STICK), planks=sample.is(ItemTags.PLANKS);
        if(!rod&&!bed&&!sticks&&!planks)return List.of();
        if(rod&&free.applyAsInt(new ItemFingerprint("minecraft:string",0))<2)return List.of();
        var actual=SupplyFlowService.container(MinecraftApi.server(owner),stock);if(actual==null)return List.of();
        var stocks=new TreeMap<String,ItemStack>();
        for(int slot=0;slot<actual.getContainerSize();slot++){
            var item=actual.getItem(slot);if(item.isEmpty()||!item.getComponentsPatch().isEmpty())continue;
            stocks.put(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),item.copyWithCount(1));
        }
        var stick=new ItemFingerprint("minecraft:stick",0);
        if(rod&&free.applyAsInt(stick)>=3)return List.of();
        if(sticks||rod){
            try{return List.of(select(owner,stock,stick,now));}catch(IllegalArgumentException missingPlanks){/* inspect real logs only */}
        }else if(bed){
            try{select(owner,stock,requested,now);return List.of();}catch(IllegalArgumentException missingInput){/* require real matching wool below */}
        }
        for(var log:stocks.values()){
            var plankSpec=lawfulPlanksFromLog(owner,stock,log,now);if(plankSpec==null)continue;
            var plank=plankSpec.output().fingerprint();
            if(planks){if(plank.equals(requested))return List.of(plankSpec);continue;}
            if(sticks||rod){
                var stickSpec=new Spec(WorkshopTask.STICKS,List.of(new ProductionMaterial(plank,2)),new ProductionMaterial(stick,4),200);
                if(nativeOutput(MinecraftApi.server(owner),stickSpec))return List.of(plankSpec,stickSpec);
            }else for(var wool:stocks.values()){
                if(!wool.is(ItemTags.WOOL)||free.applyAsInt(ItemFingerprint.of(wool))<3)continue;
                var bedSpec=new Spec(WorkshopTask.BED,List.of(new ProductionMaterial(plank,3),material(wool,3)),new ProductionMaterial(requested,1),400);
                if(nativeOutput(MinecraftApi.server(owner),bedSpec))return List.of(plankSpec);
            }
        }
        return List.of();
    }
    public static Spec select(NpcBase owner,FieldworkStorage stock,ItemFingerprint requested,long now) {
        int quantity=outputQuantity(requested);if(quantity==0)throw new IllegalArgumentException("not an existing wood product");
        var box=SupplyFlowService.container(MinecraftApi.server(owner),stock);if(box==null)throw new IllegalArgumentException("original stock unavailable");
        var principal=SubjectRef.character(owner.getCharacterId());var candidates=new TreeMap<String,ItemStack>();
        for(int i=0;i<box.getContainerSize();i++) {
            var item=box.getItem(i);if(item.isEmpty()||!item.getComponentsPatch().isEmpty())continue;
            if(SupplyFlowService.available(MinecraftApi.server(owner),stock,principal,ItemFingerprint.of(item),now)>=2)
                candidates.put(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),item.copyWithCount(1));
        }
        String kind=quantity==12?WorkshopTask.PLANKS:quantity==4?WorkshopTask.STICKS:WorkshopTask.BED;
        var output=new ProductionMaterial(requested,quantity);
        for(var first:candidates.values()) {
            if(SupplyFlowService.available(MinecraftApi.server(owner),stock,principal,ItemFingerprint.of(first),now)<(quantity==4?2:3))continue;
            if(quantity!=1) {
                var spec=new Spec(kind,List.of(material(first,quantity==4?2:3)),output,200);
                if(nativeOutput(MinecraftApi.server(owner),spec))return spec;
            } else for(var second:candidates.values()) {
                if(SupplyFlowService.available(MinecraftApi.server(owner),stock,principal,ItemFingerprint.of(second),now)<3)continue;
                var spec=new Spec(kind,List.of(material(first,3),material(second,3)),output,400);
                if(nativeOutput(MinecraftApi.server(owner),spec))return spec;
            }
        }
        // A white-bed order is also the source of its wool material request. Requiring
        // all wool before planning the order would prevent that request from existing.
        // Bind real, unreserved planks to a verified recipe; its missing wool remains
        // an obligation, never stock. Execution still requires all actual materials.
        var whiteWool=new ItemFingerprint("minecraft:white_wool",0);
        if(sample(output).is(Items.WHITE_BED)
                &&SupplyFlowService.available(MinecraftApi.server(owner),stock,principal,whiteWool,now)<3) {
            for(var plank:candidates.values()) {
                if(!plank.is(ItemTags.PLANKS)
                        ||SupplyFlowService.available(MinecraftApi.server(owner),stock,principal,ItemFingerprint.of(plank),now)<3)continue;
                var spec=new Spec(WorkshopTask.BED,List.of(material(plank,3),new ProductionMaterial(whiteWool,3)),output,400);
                if(nativeOutput(MinecraftApi.server(owner),spec))return spec;
            }
            // Both inputs may be missing, but only actual lawful logs can bind a
            // concrete plank type. The bed's material requests arrange the existing
            // three-log/twelve-board child and wool work; this does not issue either.
            for(var log:candidates.values()) {
                var planks=lawfulPlanksFromLog(owner,stock,log,now);if(planks==null)continue;
                var spec=new Spec(WorkshopTask.BED,List.of(new ProductionMaterial(planks.output().fingerprint(),3),new ProductionMaterial(whiteWool,3)),output,400);
                if(nativeOutput(MinecraftApi.server(owner),spec))return spec;
            }
        }
        throw new IllegalArgumentException("no lawful concrete native inputs for requested wood product");
    }
}
