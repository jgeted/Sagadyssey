package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.social.production.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalWorkshopNeedsTest {
    private final UUID actor=UUID.randomUUID(),stock=UUID.randomUUID();
    private final ItemFingerprint stick=new ItemFingerprint("minecraft:stick",0);
    private NeedRequest need(NeedRequestData data,String purpose,int count) {
        return data.openOrUpdate(new NeedRequest.Key(actor,purpose,stick,stock,10,1000),count,32,10);
    }
    @Test void nativeBatchDependencyDoesNotResizeOrDeliverSmallMaterialParentAndSurvivesNbt() {
        var data=new NeedRequestData();var parent=need(data,"CHARACTER:production:"+UUID.randomUUID(),3);
        var child=need(data,"CHARACTER:local-workshop:"+parent.id()+":minecraft:stick",4);
        assertTrue(data.dependOn(parent.id(),child.id(),10));
        assertTrue(LocalWorkshopNeeds.activeChild(child,actor,stock,stick));
        assertFalse(LocalWorkshopNeeds.eligible(child,20));
        assertFalse(LocalWorkshopNeeds.activeChild(child,UUID.randomUUID(),stock,stick));
        assertFalse(LocalWorkshopNeeds.activeChild(child,actor,UUID.randomUUID(),stick));
        assertFalse(LocalWorkshopNeeds.activeChild(child,actor,stock,new ItemFingerprint("minecraft:birch_planks",0)));
        data=NeedRequestData.load(data.save(new CompoundTag(),null),null);
        assertEquals(Set.of(child.id()),data.dependencies(parent.id()));
        assertEquals(3,data.request(parent.id()).requested());assertEquals(0,data.request(parent.id()).delivered());
        assertEquals(4,data.request(child.id()).requested());assertEquals(2,data.requests().size());
    }
    @Test void originalWorkThatAlreadyOwnsInputsNeverTriggersReplacementFromReducedAvailableStock() {
        var owner=SubjectRef.character(actor);
        var order=ProductionOrder.open(UUID.randomUUID(),owner,owner,"sagadyssey:workshop_fishing_rod",
                List.of(new ProductionMaterial(stick,3)),new ProductionMaterial(new ItemFingerprint("minecraft:fishing_rod",0),1),
                200,UUID.randomUUID(),"minecraft:overworld",BlockPos.ZERO,stock,"minecraft:overworld",BlockPos.ZERO,10,"original");
        assertTrue(LocalWorkshopNeeds.openWithoutEffects(order,false,owner,stock));
        assertFalse(LocalWorkshopNeeds.openWithoutEffects(order,true,owner,stock));
        assertFalse(LocalWorkshopNeeds.openWithoutEffects(order,false,owner,UUID.randomUUID()));
        assertFalse(LocalWorkshopNeeds.openWithoutEffects(order,false,SubjectRef.character(UUID.randomUUID()),stock));
        order=order.claim(actor,20,"real acceptance");
        assertFalse(LocalWorkshopNeeds.openWithoutEffects(order,false,owner,stock));
        order=order.transition(ProductionOrderStatus.RESERVED,21,"real input lease");
        assertFalse(LocalWorkshopNeeds.openWithoutEffects(order,true,owner,stock));
        order=order.transition(ProductionOrderStatus.IN_PROGRESS,22,"labour");
        assertFalse(LocalWorkshopNeeds.openWithoutEffects(order,true,owner,stock));
    }
    @Test void expiryCannotDiscardBoundUncertainHeldOrCarriedObligations() {
        var data=new NeedRequestData();var child=need(data,"CHARACTER:local-workshop:parent:minecraft:stick",4);
        assertTrue(LocalWorkshopNeeds.expireUncommitted(child,false,false,1000));
        assertFalse(LocalWorkshopNeeds.expireUncommitted(child,true,false,1000));
        assertFalse(LocalWorkshopNeeds.expireUncommitted(child,false,true,1000));
        child=data.acknowledge(UUID.randomUUID(),child.id(),NeedRequestData.Action.RESERVE,4,20);
        assertFalse(LocalWorkshopNeeds.expireUncommitted(child,false,false,1000));
        child=data.acknowledge(UUID.randomUUID(),child.id(),NeedRequestData.Action.DISPATCH,4,21);
        assertFalse(LocalWorkshopNeeds.expireUncommitted(child,false,false,1000));
    }
    @Test void fulfilledOrUncertainParentCannotRecursivelyCreateAnotherPlan() {
        var data=new NeedRequestData();var parent=need(data,"CHARACTER:production:"+UUID.randomUUID(),3);
        assertTrue(LocalWorkshopNeeds.eligible(parent,20));
        parent=data.transition(parent.id(),NeedRequestStatus.UNREACHABLE,21,"PRODUCTION_REVIEW:receipt missing");
        assertFalse(LocalWorkshopNeeds.eligible(parent,22));
        assertFalse(LocalWorkshopNeeds.eligible(need(data,"CHARACTER:local-supplier:some-parent",3),22));
        assertFalse(LocalWorkshopNeeds.eligible(parent,1000));
    }
}
