package com.jgeted.sagadyssey.social.war;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMapNetwork12111.*;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction.*;
import io.netty.buffer.*;
import io.netty.handler.codec.DecoderException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.jgeted.sagadyssey.social.war.WarMapServiceTest.*;

class WarMapCodecTest {
    @Test void requestRoundTripsAllModesWithoutClientWorldCoordinates(){for(var a:Action.values())for(var m:WarMapView.Mode.values()){var v=new RequestPayload(new Request(ACCOUNT,1,a,2,UNIT,m,0,127,ACTOR));var b=Unpooled.buffer();try{RequestPayload.CODEC.encode(b,v);assertEquals(v,RequestPayload.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}}
    @Test void malformedOrdinalsAndUnboundedPixelsAreRejected(){var good=new RequestPayload(new Request(ACCOUNT,1,Action.PREVIEW,0,UNIT,WarMapView.Mode.HOLD,0,0,ACTOR));var b=Unpooled.buffer();try{RequestPayload.CODEC.encode(b,good);b.setByte(24,255);assertThrows(DecoderException.class,()->RequestPayload.CODEC.decode(b));}finally{b.release();}assertThrows(IllegalArgumentException.class,()->new Request(ACCOUNT,1,Action.PREVIEW,0,UNIT,WarMapView.Mode.HOLD,128,0,ACTOR));}
    @Test void sixUnitProjectionRoundTripsReportsPreviewAndReceiptUnder16KiB(){var rows=new ArrayList<WarMapView.Unit>();for(int i=0;i<6;i++)rows.add(new WarMapView.Unit(new UUID(1,i),ARMY,WarKey.parse("sagadyssey:war/troop/archer"),1,3,2,6500,1,Optional.of(new WarMapView.Observation(new WarIntent.Position(DIM,10,65,-10),99,WarFieldReport.State.HOLDING))));var p=new WarMapView.Preview(ACTOR,UNIT,WarMapView.Mode.HOLD,new WarIntent.Position(DIM,4,65,4),220,WarMapView.Delivery.COURIER,50);var v=new UpdatePayload(new Update(ACCOUNT,true,Optional.of(new WarMapView(ACCOUNT,1,100,700,sheet(),0,7,rows,Optional.of(p),Optional.of(accepted(command(ACTOR))))),"己方报告"));var b=Unpooled.buffer();try{UpdatePayload.CODEC.encode(b,v);assertTrue(b.readableBytes()<16384);assertEquals(v,UpdatePayload.CODEC.decode(b));}finally{b.release();}}
    @Test void oversizedPageIsRejectedBeforeAllocatingRows(){var b=Unpooled.buffer();try{b.writeLong(0).writeLong(1).writeBoolean(false).writeInt(0).writeBoolean(true);b.writeLong(1).writeLong(100).writeLong(700).writeInt(1);byte[] dim="minecraft:overworld".getBytes(java.nio.charset.StandardCharsets.UTF_8);b.writeInt(dim.length).writeBytes(dim).writeInt(0).writeInt(0).writeByte(0).writeInt(0).writeInt(128).writeByte(255);assertThrows(DecoderException.class,()->UpdatePayload.CODEC.decode(b));}finally{b.release();}}
    @Test void truncatedAndOverlongStringsCannotAllocateUnboundedBuffers(){var b=Unpooled.buffer();try{b.writeLong(0).writeLong(1).writeBoolean(false).writeInt(Integer.MAX_VALUE);assertThrows(DecoderException.class,()->UpdatePayload.CODEC.decode(b));}finally{b.release();}var shortBuffer=Unpooled.buffer();try{shortBuffer.writeLong(1);assertThrows(IndexOutOfBoundsException.class,()->RequestPayload.CODEC.decode(shortBuffer));}finally{shortBuffer.release();}}
    @Test void projectionRejectsIncompletePagesAndForeignDimensionObservations(){assertThrows(IllegalArgumentException.class,()->new WarMapView(ACCOUNT,1,100,700,sheet(),0,1,List.of(),Optional.empty(),Optional.empty()));var u=new WarMapView.Unit(UNIT,ARMY,WarKey.parse("sagadyssey:war/troop/archer"),1,1,1,-1,0,Optional.of(new WarMapView.Observation(new WarIntent.Position(WarKey.parse("minecraft:the_nether"),0,65,0),99,WarFieldReport.State.HOLDING)));assertThrows(IllegalArgumentException.class,()->new WarMapView(ACCOUNT,1,100,700,sheet(),0,1,List.of(u),Optional.empty(),Optional.empty()));}
}
