package com.jgeted.sagadyssey.social.growth;

import com.jgeted.sagadyssey.npc.brain.fieldwork.FieldworkRoutes;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.worldsim.lod.NpcNavigationQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** 已加载地面上的有限绕行；不挖路、不传送、不把导航范围当成资源权限。 */
public final class SettlementSurveyNavigation {
    private SettlementSurveyNavigation() { }
    private record Route(BlockPos target, ArrayList<BlockPos> path, long expires) { }
    private static final Map<NpcBase, Route> ROUTES = new WeakHashMap<>();
    private static final Map<NpcBase, String> STATUS = new WeakHashMap<>();
    public static String status(NpcBase npc) { return STATUS.getOrDefault(npc, "尚未寻路"); }

    public static boolean move(NpcBase npc, BlockPos target) {
        long now = npc.level().getGameTime();
        // 原版路径抵达容差可能停在门内一格；最后几步仍通过真实移动和逐段碰撞检查完成。
        var delta = net.minecraft.world.phys.Vec3.atBottomCenterOf(target).subtract(npc.position());
        boolean clear = npc.onGround() && Math.abs(delta.y) < .1 && delta.lengthSqr() < 9;
        int segments = Math.max(8, (int) Math.ceil(delta.length() * 8));
        for (int i = 1; clear && i <= segments; i++) {
            var offset = delta.scale(i / (double) segments);
            clear = npc.level().noCollision(npc, npc.getBoundingBox().move(offset))
                    && walkable(npc, BlockPos.containing(npc.position().add(offset)));
        }
        if (clear) {
            npc.getNavigation().stop();
            npc.getMoveControl().setWantedPosition(target.getX() + .5, target.getY(), target.getZ() + .5, 1D);
            STATUS.put(npc, "通过碰撞检查后实际走完近处站位=" + target.toShortString());
            return true;
        }
        // Once the real native navigator can reach a local destination, keep that
        // destination. A cached detour must not pull the body back to an old node.
        if(npc.blockPosition().distSqr(target)<=12D*12D){
            var existing=npc.getNavigation().getPath();
            if(existing!=null&&!npc.getNavigation().isDone()&&existing.getTarget().equals(target))return true;
            var nativePath=npc.getNavigation().createPath(target,0);
            if(nativePath!=null&&nativePath.canReach()){
                ROUTES.remove(npc);STATUS.put(npc,"原版已确认完整近程目标="+target.toShortString());
                return NpcNavigationQueue.move(npc,target,1D);
            }
        }
        var cached = ROUTES.get(npc);
        if (cached == null || !cached.target().equals(target) || now >= cached.expires()) {
            cached = new Route(target.immutable(), search(npc, target), now + 200);
            ROUTES.put(npc, cached);
        }
        var route = cached.path();
        pruneReached(route,npc.position());
        // 只提交原版实际可达的近处站位，不能把抽象地面连通等同于实体已抵达。
        for (int index = Math.min(6, route.size() - 1); index >= 0; index--) {
            var point = route.get(index);
            if (!walkable(npc, point)) continue;
            var path = npc.getNavigation().createPath(point, 0);
            if (path != null && path.canReach()) {
                STATUS.put(npc, "原版可达站位=" + point.toShortString() + ";剩余路径=" + route.size());
                return NpcNavigationQueue.move(npc, point, 1D);
            }
        }
        STATUS.put(npc, "无原版可达近处站位;地面路径=" + route.stream().limit(8).toList() + ";目标=" + target.toShortString());
        return false;
    }

    /** Native paths can legitimately cut grid corners and skip the first node.
     * Drop a prefix only when the actual body reached a later recorded point. */
    static void pruneReached(ArrayList<BlockPos> path,net.minecraft.world.phys.Vec3 actual){
        int reached=-1;
        for(int i=0;i<path.size();i++)if(actual.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(path.get(i)))<.36)reached=i;
        if(reached>=0)path.subList(0,reached+1).clear();
    }

    private static boolean walkable(NpcBase npc, BlockPos point) {
        return walkable(npc, point, null);
    }

    private static boolean walkable(NpcBase npc, BlockPos point, BlockPos removed) {
        var level = npc.level();
        for (var cell : new BlockPos[]{point, point.above()}) {
            if (cell.equals(removed)) continue;
            var state = level.getBlockState(cell);
            boolean openDoor = state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN);
            if ((!openDoor && !state.getCollisionShape(level, cell).isEmpty()) || !state.getFluidState().isEmpty()) return false;
        }
        var floor = point.below();
        var support=level.getBlockState(floor);
        return !floor.equals(removed) && level.getBlockEntity(floor) == null
                && (support.isFaceSturdy(level, floor, Direction.UP)||support.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                    ||support.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))
                && level.getFluidState(floor).isEmpty();
    }

    static boolean connected(NpcBase npc, BlockPos start, BlockPos target, BlockPos removed) {
        if (!walkable(npc, start, removed)) return false;
        var path = search(npc, start, target, removed);
        return start.equals(target) || !path.isEmpty() && path.getLast().equals(target);
    }

    private static ArrayList<BlockPos> search(NpcBase npc, BlockPos target) {
        return search(npc, npc.blockPosition(), target, null);
    }

    private static ArrayList<BlockPos> search(NpcBase npc, BlockPos start, BlockPos target, BlockPos removed) {
        var frontier = new ArrayDeque<BlockPos>();
        var previous = new HashMap<BlockPos, BlockPos>();
        frontier.add(start); previous.put(start, null);
        BlockPos best = start;
        while (!frontier.isEmpty() && previous.size() < 4096) {
            var point = frontier.removeFirst();
            if (point.distSqr(target) < best.distSqr(target)) best = point;
            if (point.equals(target)) { best = point; break; }
            for (var direction : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0, 1, -1}) {
                var next = point.relative(direction).offset(0, dy, 0);
                if (Math.abs(next.getX() - start.getX()) > 32 || Math.abs(next.getZ() - start.getZ()) > 32
                        || Math.abs(next.getY() - start.getY()) > 8 || previous.containsKey(next)) continue;
                var level = (ServerLevel) npc.level();
                if (level.getChunkSource().getChunkNow(next.getX() >> 4, next.getZ() >> 4) == null
                        || !walkable(npc, next, removed)) continue;
                if (dy > 0 && !point.above(2).equals(removed)
                        && !level.getBlockState(point.above(2)).getCollisionShape(level, point.above(2)).isEmpty()) continue;
                previous.put(next, point); frontier.addLast(next); break;
            }
        }
        var path = new ArrayList<BlockPos>();
        if (best.equals(start)) return path;
        for (var point = best; point != null && !point.equals(start); point = previous.get(point)) path.addFirst(point);
        return path;
    }
}
