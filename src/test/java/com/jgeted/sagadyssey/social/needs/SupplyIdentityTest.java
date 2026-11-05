package com.jgeted.sagadyssey.social.needs;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SupplyIdentityTest {
    private SupplyFlowData.Route route(UUID id,UUID need,UUID source,UUID target,int quantity,UUID carrier){
        return new SupplyFlowData.Route(id,need,source,target,SubjectRef.character(target),quantity,carrier,carrier,SupplyFlowData.Stage.HELD,0,0,null,"held");
    }
    @Test void registeredShipmentCannotChangeNeedOrQuantity(){
        var d=new SupplyFlowData();UUID id=UUID.randomUUID(),need=UUID.randomUUID(),source=UUID.randomUUID(),target=UUID.randomUUID();
        d.route(route(id,need,source,target,3,null));
        assertThrows(IllegalStateException.class,()->d.route(route(id,UUID.randomUUID(),source,target,3,null)));
        assertThrows(IllegalStateException.class,()->d.route(route(id,need,source,target,4,null)));
    }
    @Test void claimedShipmentCannotBeTakenBySecondBody(){
        var d=new SupplyFlowData();UUID id=UUID.randomUUID(),need=UUID.randomUUID(),source=UUID.randomUUID(),target=UUID.randomUUID(),carrier=UUID.randomUUID();
        d.route(route(id,need,source,target,3,null));d.route(route(id,need,source,target,3,carrier));
        assertThrows(IllegalStateException.class,()->d.route(route(id,need,source,target,3,UUID.randomUUID())));
        assertThrows(IllegalStateException.class,()->d.route(route(id,need,source,target,3,null)));
    }
    @Test void merchantRouteCannotSwapMerchantOrPurchasedQuantity(){
        var d=new MerchantSupplyData();UUID id=UUID.randomUUID(),body=UUID.randomUUID(),character=UUID.randomUUID(),buy=UUID.randomUUID(),sell=UUID.randomUUID();
        var original=new MerchantSupplyData.Journey(id,body,character,buy,sell,3,MerchantSupplyData.Stage.BUY,null,0,0,0,0,0,"new");d.journey(original);
        assertThrows(IllegalStateException.class,()->d.journey(new MerchantSupplyData.Journey(id,UUID.randomUUID(),character,buy,sell,3,original.stage(),null,0,0,0,0,0,"changed")));
        assertThrows(IllegalStateException.class,()->d.journey(new MerchantSupplyData.Journey(id,body,character,buy,sell,4,original.stage(),null,0,0,0,0,0,"changed")));
    }
}
