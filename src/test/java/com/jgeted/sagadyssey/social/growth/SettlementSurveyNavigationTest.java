package com.jgeted.sagadyssey.social.growth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
final class SettlementSurveyNavigationTest {
    @Test void actualNativeCornerCutDoesNotSendBodyBackToSkippedGridPrefix(){
        var path=new ArrayList<>(List.of(new BlockPos(1,0,0),new BlockPos(1,0,1),new BlockPos(2,0,1),new BlockPos(3,0,1)));
        SettlementSurveyNavigation.pruneReached(path,new Vec3(2.5,0,1.5));
        assertEquals(List.of(new BlockPos(3,0,1)),path);
    }
    @Test void beingCloserToDestinationIsNotEvidenceOfReachingACachedPoint(){
        var original=List.of(new BlockPos(1,0,0),new BlockPos(2,0,0),new BlockPos(3,0,0));var path=new ArrayList<>(original);
        SettlementSurveyNavigation.pruneReached(path,new Vec3(3.5,0,2.5));assertEquals(original,path);
    }
}
