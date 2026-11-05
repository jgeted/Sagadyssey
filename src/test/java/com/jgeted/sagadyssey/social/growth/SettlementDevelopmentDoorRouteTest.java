package com.jgeted.sagadyssey.social.growth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class SettlementDevelopmentDoorRouteTest {
 @Test void offsetBodyMustAlignBeforePassingDoorFrame(){
  var inside=new BlockPos(0,0,1);var outside=new BlockPos(0,0,-1);
  assertEquals(inside,SettlementDevelopmentData.doorWaypoint(new Vec3(.97,0,1.3),inside,outside));
 }
 @Test void crossingDoesNotTurnBackWhenDistanceToStartIncreases(){
  var inside=new BlockPos(0,0,1);var outside=new BlockPos(0,0,-1);
  for(double z=1.5;z>=-.5;z-=.1)assertEquals(outside,SettlementDevelopmentData.doorWaypoint(new Vec3(.5,0,z),inside,outside));
 }
 @Test void bothDirectionsAndRotatedDoorwaysUseSamePhysicalAlignment(){
  var a=new BlockPos(-4,-60,8);var b=new BlockPos(-2,-60,8);
  assertEquals(b,SettlementDevelopmentData.doorWaypoint(Vec3.atBottomCenterOf(a),a,b));
  assertEquals(a,SettlementDevelopmentData.doorWaypoint(Vec3.atBottomCenterOf(b),b,a));
  assertEquals(a,SettlementDevelopmentData.doorWaypoint(Vec3.atBottomCenterOf(a).add(0,0,.4),a,b));
 }
}
