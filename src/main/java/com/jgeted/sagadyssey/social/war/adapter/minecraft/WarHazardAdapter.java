package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.social.war.api.*;
import net.minecraft.world.level.pathfinder.Path;
import java.util.*;

/** Version boundary for actual first-visible-face sensing and dated army-local route caution. */
public interface WarHazardAdapter {
    record Sight(WarIntent.Position position,Optional<WarKey> kind,String state){}
    Optional<Sight> see(NpcBase worker,WarIntent.Position target);
    Optional<WarIntent.Position> safeStand(NpcBase worker,WarIntent.Position target);
    Optional<WarKey> kind(String state,NpcBase worker);
    boolean mayStand(NpcBase worker,WarIntent.Position feet);
    boolean mayFollow(NpcBase worker,Path path);
}
