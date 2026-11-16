package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.execution.NpcEntityExecutionContext;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.ContainerIds;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native-only engineering boundary. The original Fieldwork commit owns inventory, tool wear, outputs and rights. */
public final class WarBlockWork12111 implements com.jgeted.sagadyssey.social.war.adapter.minecraft.WarEngineeringAdapter {
    public static final String MARKER="SagadysseyWarEngineeringV1";
    private static final Set<Block> MATERIALS=Set.of(Blocks.COBBLESTONE,Blocks.STONE_BRICKS,Blocks.OAK_PLANKS,Blocks.DIRT);
    private static final Set<Block> EXCAVATE=Set.of(Blocks.STONE,Blocks.COBBLESTONE,Blocks.DIRT,Blocks.GRASS_BLOCK,Blocks.COARSE_DIRT,Blocks.DEEPSLATE,Blocks.COBBLED_DEEPSLATE);
    public WarBlockWork12111(){}
    public List<Material> buildMaterials() {
        return MATERIALS.stream().map(block -> new Material(
                WarKey.parse(BuiltInRegistries.BLOCK.getKey(block).toString()), block.getName().getString()))
                .sorted(Comparator.comparing(material -> material.key().toString())).toList();
    }
    public String markerKey(){return MARKER;}
    public BlockPos position(WarEngineeringJob.Cell c){var p=c.position();return new BlockPos(p.x(),p.y(),p.z());}
    public BlockState state(ServerLevel level,String encoded){try{return NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),TagParser.parseCompoundFully(encoded));}catch(Exception e){throw new IllegalArgumentException("invalid original block identity",e);}}
    public String state(BlockState state){return NbtUtils.writeBlockState(state).toString();}
    public List<WarEngineeringJob.Cell> programCells(NpcBase npc,WarEngineeringLayouts.Layout layout){
        var result=new ArrayList<WarEngineeringJob.Cell>();
        for(int i=0;i<layout.targets().size();i++){var step=layout.program().steps().get(i);var desired=step.mode()==WarEngineeringJob.Mode.DIG?Blocks.AIR.defaultBlockState():BuiltInRegistries.BLOCK.getValue(Identifier.parse(step.material().orElseThrow().toString())).defaultBlockState();
            result.add(new WarEngineeringJob.Cell(layout.targets().get(i),WarEngineeringJob.UNOBSERVED,state(desired),20));}
        return List.copyOf(result);
    }
    public Inspection inspect(NpcBase npc,WarEngineeringJob job){
        var c=job.cells().get(job.next());var at=position(c);var l=(ServerLevel)npc.level();
        if(!loaded(l,at)||!c.position().dimension().toString().equals(l.dimension().identifier().toString()))return blocked("工作面尚未加载，等待原区块");
        if(!reachable(npc,c))return blocked("须实际走到已批准站位并看见当前工作面");
        var before=l.getBlockState(at);var desired=state(l,c.after());
        if(before.equals(desired)||job.stepMode()==WarEngineeringJob.Mode.DIG&&before.isAir())return new Inspection(Optional.empty(),true,"现场已达到目标，不扣料、不记新施工");
        var program=job.program().orElseThrow();boolean keepSupport=program.kind()==WarEngineeringProgram.Kind.TUNNEL&&job.next()%4==2&&before.is(Blocks.OAK_PLANKS)
                ||program.kind()==WarEngineeringProgram.Kind.SPIKE_PIT&&job.next()%3==1&&before.equals(Blocks.POINTED_DRIPSTONE.defaultBlockState());
        if(keepSupport)return new Inspection(Optional.empty(),true,"保留已经符合本模板的支护或石刺，不拆除重建");
        if(program.evidence().filter(o->!o.state().equals(state(before))||l.getGameTime()<o.observedAt()||l.getGameTime()-o.observedAt()>2400).isPresent())return blocked("现场或侦察时效已改变，请重新侦察并批准排障");
        if(before.hasBlockEntity()||l.getBlockEntity(at)!=null||!before.getFluidState().isEmpty()
                ||job.stepMode()==WarEngineeringJob.Mode.BUILD&&!before.isAir()||job.stepMode()==WarEngineeringJob.Mode.DIG&&!digAllowed(job,before))return blocked("已暴露工作面不属于批准的普通方块或排障目标");
        int ticks=20;
        if(job.stepMode()==WarEngineeringJob.Mode.DIG)try{ticks=workTicks(npc,before,at);}catch(IllegalArgumentException missing){return blocked("请实际交付有使用权的工具；缠阻网排除需要剪刀");}
        return new Inspection(Optional.of(new WarEngineeringJob.Cell(c.position(),state(before),c.after(),ticks)),false,"当前工作面已实际观察，待保存后施工");
    }
    private static Inspection blocked(String reason){return new Inspection(Optional.empty(),false,reason);}
    public boolean occupiesWorkSurface(NpcBase npc,WarEngineeringJob job){
        var box=new AABB(position(job.cells().get(job.next())));
        return npc.getBoundingBox().intersects(job.stepMode()==WarEngineeringJob.Mode.BUILD?box:box.move(0,1,0));
    }
    private boolean digAllowed(WarEngineeringJob job,BlockState before){
        if(job.program().map(p->p.kind()==WarEngineeringProgram.Kind.DISARM).orElse(false))return before.is(Blocks.COBWEB)
                ||before.equals(Blocks.POINTED_DRIPSTONE.defaultBlockState());
        return EXCAVATE.contains(before.getBlock());
    }
    private boolean buildAllowed(WarEngineeringJob job,BlockState desired){
        if(job.program().isEmpty())return MATERIALS.contains(desired.getBlock());
        var item=job.stepMaterial().orElseThrow().toString();
        return switch(job.program().get().kind()){
            case SPIKE_PIT->item.equals("minecraft:pointed_dripstone")&&desired.equals(Blocks.POINTED_DRIPSTONE.defaultBlockState());
            case WEB_SNARE->item.equals("minecraft:cobweb")&&desired.equals(Blocks.COBWEB.defaultBlockState());
            case TUNNEL->item.equals("minecraft:oak_planks")&&desired.equals(Blocks.OAK_PLANKS.defaultBlockState());
            case DISARM->false;
        };
    }
    public List<WarEngineeringJob.Cell> preview(ServerPlayer owner,NpcBase npc,WarIntent.Position a,WarIntent.Position b,WarEngineeringJob.Mode mode,Optional<WarKey> material){
        var level=(ServerLevel)npc.level();BlockState result=Blocks.AIR.defaultBlockState();
        if(mode==WarEngineeringJob.Mode.BUILD){var block=BuiltInRegistries.BLOCK.getValue(Identifier.parse(material.orElseThrow().toString()));if(!MATERIALS.contains(block))throw new IllegalArgumentException("首版材料限圆石、石砖、橡木木板和泥土");result=block.defaultBlockState();}
        var cells=new ArrayList<WarEngineeringJob.Cell>();
        for(var p:WarEngineeringGeometry.box(a,b,mode)){
            var at=new BlockPos(p.x(),p.y(),p.z());if(!p.dimension().toString().equals(level.dimension().identifier().toString())||!loaded(level,at)||!level.getWorldBorder().isWithinBounds(at)||at.getY()<level.getMinY()||at.getY()>=level.getMaxY())throw new IllegalArgumentException("工程范围须在当前维度的已加载世界边界内");
            if(npc.distanceToSqr(at.getX()+.5,at.getY()+.5,at.getZ()+.5)>128*128||owner.distanceToSqr(at.getX()+.5,at.getY()+.5,at.getZ()+.5)>128*128||!permission(owner,level,at)||!landAllowed(npc,at))throw new IllegalArgumentException("工程须在128格内并通过原领主、原聚落和建筑的施工权限");
            var before=level.getBlockState(at);if(before.equals(result))continue;
            if(before.hasBlockEntity()||!before.getFluidState().isEmpty()||mode==WarEngineeringJob.Mode.BUILD&&!before.isAir()||mode==WarEngineeringJob.Mode.DIG&&!EXCAVATE.contains(before.getBlock()))throw new IllegalArgumentException("不覆盖已有建筑、容器、流体或未开放的挖掘目标");
            if(level.getBlockEntity(at)!=null)throw new IllegalArgumentException("工程不能包含原容器或方块实体");
            int ticks=mode==WarEngineeringJob.Mode.BUILD?20:workTicks(npc,before,at);cells.add(new WarEngineeringJob.Cell(p,state(before),state(result),ticks));
        }
        if(cells.isEmpty())throw new IllegalArgumentException("区域已经达到目标，没有需要付费施工的方块");return List.copyOf(cells);
    }
    public int workTicks(NpcBase npc,BlockState block,BlockPos at){
        var rights=RightsStateData.get(npc.getServer());var id=ContainerIds.npcPersonal(npc.getCharacterId());float best=0;
        for(int i=npc.getPersonalInventory().getContainerSize()-1;i>=0;i--){var tool=npc.getPersonalInventory().getItem(i);if(tool.isEmpty()||!FieldworkWorldMutation.excavationTool(tool,block)||block.requiresCorrectToolForDrops()&&!tool.isCorrectToolForDrops(block))continue;
            if(rights.context(id)!=null&&new RightsService(rights).can(SubjectRef.character(npc.getCharacterId()),AssetRight.USE,id,i,ItemFingerprint.of(tool),npc.level().getGameTime()).status()!=RightsVerdict.Status.ALLOW)continue;
            best=tool.getDestroySpeed(block);break;
        }
        if(best<=0)throw new IllegalArgumentException("原背包缺少有使用权的适用工具，请先实际交付工具");float hardness=block.getDestroySpeed(npc.level(),at);if(hardness<0)throw new IllegalArgumentException("不可破坏方块");return Math.max(1,Math.min(24000,(int)Math.ceil(hardness*30/Math.max(.01F,best))));
    }
    public boolean loaded(ServerLevel level,BlockPos at){return level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)!=null;}
    public boolean permission(ServerPlayer owner,ServerLevel level,BlockPos at){return owner!=null&&owner.level()==level&&owner.isAlive()&&level.mayInteract(owner,at)&&!level.getServer().isUnderSpawnProtection(level,at,owner);}
    private static boolean landAllowed(NpcBase npc,BlockPos at){return com.jgeted.sagadyssey.social.growth.SettlementGrowthData.workAllowed(npc,at)&&com.jgeted.sagadyssey.core.structure.NaturalVillageLifeData.cultivationAllowed(npc,at);}
    public boolean reachable(NpcBase npc,WarEngineeringJob.Cell c){
        var at=position(c);if(!c.position().dimension().toString().equals(npc.level().dimension().identifier().toString())||!loaded((ServerLevel)npc.level(),at)||npc.distanceToSqr(at.getX()+.5,at.getY()+.5,at.getZ()+.5)>9)return false;
        return visibleFrom(npc,npc.getEyePosition(),at);
    }
    public boolean occupiesTarget(NpcBase npc,WarEngineeringJob.Cell cell){return npc.getBoundingBox().intersects(new AABB(position(cell)));}
    public boolean centerOnStand(NpcBase npc,BlockPos stand){
        if(Math.abs(npc.getY()-stand.getY())>=.4||npc.distanceToSqr(stand.getX()+.5,stand.getY(),stand.getZ()+.5)>=2.25||!FieldworkRoutes.walkable(npc,stand))return false;
        // As in original civilian fieldwork, native navigation stops at a node edge. Walk the final safe fraction.
        npc.getNavigation().stop();npc.getMoveControl().setWantedPosition(stand.getX()+.5,stand.getY(),stand.getZ()+.5,.8);return true;
    }
    /** The next standing place must see the work face too; an adjacent square can be behind a completed wall. */
    public boolean workableFrom(NpcBase npc,BlockPos feet,WarEngineeringJob.Cell cell){
        var at=position(cell);return Vec3.atBottomCenterOf(feet).distanceToSqr(Vec3.atCenterOf(at))<=9&&visibleFrom(npc,Vec3.atBottomCenterOf(feet).add(0,npc.getEyeHeight(),0),at);
    }
    static boolean visibleFrom(NpcBase npc,Vec3 eye,BlockPos at){
        var level=(ServerLevel)npc.level();
        int x=(int)Math.floor(eye.x),z=(int)Math.floor(eye.z);
        // A ray can cross a third chunk at a corner, even when both ends are loaded.
        for(int cx=Math.min(x,at.getX())>>4;cx<=Math.max(x,at.getX())>>4;cx++)
            for(int cz=Math.min(z,at.getZ())>>4;cz<=Math.max(z,at.getZ())>>4;cz++)
                if(level.getChunkSource().getChunkNow(cx,cz)==null)return false;
        var center=Vec3.atCenterOf(at);
        for(var aim:List.of(center,center.add(0,-.499,0),center.add(0,.499,0),center.add(-.499,0,0),center.add(.499,0,0),center.add(0,0,-.499),center.add(0,0,.499))){
            var hit=level.clip(new ClipContext(eye,aim,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,npc));
            if(hit.getType()==HitResult.Type.MISS||hit.getBlockPos().equals(at))return true;
        }
        return false;
    }
    public FieldworkAction action(WarEngineeringJob job){var c=job.cells().get(job.next());return new FieldworkAction(job.id(),job.body(),job.next(),c.position().dimension().toString(),position(c),job.stepMode()==WarEngineeringJob.Mode.BUILD?FieldworkAction.Operation.PLACE_BLOCK:FieldworkAction.Operation.EXCAVATE,c.before());}
    public boolean receiptMatches(WarEngineeringJob job,int cell,FieldworkActionReceipt receipt){var c=job.cells().get(cell);return receipt.action().planId().equals(job.id())&&receipt.action().npcId().equals(job.body())&&receipt.action().stepIndex()==cell&&receipt.action().dimension().equals(c.position().dimension().toString())&&receipt.action().target().equals(position(c))&&receipt.action().expectedState().equals(c.before())&&receipt.resultingState().equals(c.after())&&receipt.action().operation()==(job.modeAt(cell)==WarEngineeringJob.Mode.BUILD?FieldworkAction.Operation.PLACE_BLOCK:FieldworkAction.Operation.EXCAVATE);}
    public boolean marker(NpcBase npc,WarEngineeringJob job,int index){var t=npc.getPersistentData().getCompoundOrEmpty(MARKER);return t.read("Job",UUIDUtil.CODEC).filter(job.id()::equals).isPresent()&&t.getIntOr("Cell",-1)>=index&&t.getLongOr("InventoryRevision",Long.MAX_VALUE)<=npc.getPersonalInventory().revision();}
    public FieldworkCommitService.Mutation mutation(NpcBase npc,ServerPlayer owner,WarEngineeringJob job,BooleanSupplier authority){
        var c=job.cells().get(job.next());var a=action(job);var level=(ServerLevel)npc.level();var params=new HashMap<String,String>();params.put("result",c.after());job.stepMaterial().ifPresent(m->params.put("input",m.toString()));
        var nativeMutation=new FieldworkWorldMutation(new NpcEntityExecutionContext(npc,level),a,params,level.getGameTime(),()->authority.getAsBoolean()&&permission(owner,level,a.target())&&reachable(npc,c));
        var previous=npc.getPersistentData().get(MARKER);var oldMarker=previous==null?null:previous.copy();
        return new FieldworkCommitService.Mutation(){
            public Optional<FieldworkCommitService.Blocker> validate(){
                var expected=state(level,c.before());var desired=state(level,c.after());
                if(job.stepMode()==WarEngineeringJob.Mode.BUILD&&(!expected.isAir()||!buildAllowed(job,desired)||!job.stepMaterial().orElseThrow().toString().equals(BuiltInRegistries.ITEM.getKey(desired.getBlock().asItem()).toString()))||job.stepMode()==WarEngineeringJob.Mode.DIG&&(!digAllowed(job,expected)||!desired.isAir()))return Optional.of(new FieldworkCommitService.Blocker("WORK_KIND_NOT_SUPPORTED","当前工作面超出已批准的方块能力"));
                if(!authority.getAsBoolean()||!permission(owner,level,a.target())||!landAllowed(npc,a.target())||!reachable(npc,c))return Optional.of(new FieldworkCommitService.Blocker("ORIGINAL_WORK_SCOPE_REQUIRED","原授权、聚落/建筑、身体或实际作业面不可用"));
                if(!level.getBlockState(a.target()).getFluidState().isEmpty()||level.getBlockEntity(a.target())!=null)return Optional.of(new FieldworkCommitService.Blocker("TARGET_ASSET_OR_FLUID","不能消除原资产或流体"));
                if(job.stepMode()==WarEngineeringJob.Mode.DIG){
                    for(var direction:Direction.values())if(!loaded(level,a.target().relative(direction))||!level.getFluidState(a.target().relative(direction)).isEmpty())return Optional.of(new FieldworkCommitService.Blocker("FLUID_OR_UNKNOWN_NEIGHBOR","相邻流体或未加载空间，不能猜测开挖"));
                    if(!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(a.target()).move(0,1,0)).isEmpty())return Optional.of(new FieldworkCommitService.Blocker("OCCUPIED_SUPPORT","方块上仍有身体，暂停开挖"));
                    if(job.program().isPresent()&&level.getBlockState(a.target().above()).getBlock() instanceof FallingBlock)return Optional.of(new FieldworkCommitService.Blocker("FALLING_ROOF","工作面上方有会坠落的方块，先处理风险"));
                    if(job.program().flatMap(WarEngineeringProgram::evidence).filter(o->!o.state().equals(c.before())||level.getGameTime()-o.observedAt()>2400).isPresent())return Optional.of(new FieldworkCommitService.Blocker("STALE_SURVEY","排障观察已变化或过时，须重新批准"));
                }
                return nativeMutation.validate();
            }
            public FieldworkActionReceipt preview(){return nativeMutation.preview();}
            public boolean applySingleBlock(){
                if(job.stepMode()==WarEngineeringJob.Mode.DIG&&NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level,a.target(),level.getBlockState(a.target()),owner)).isCanceled())return false;
                var snapshot=BlockSnapshot.create(level.dimension(),level,a.target());if(!nativeMutation.applySingleBlock())return false;
                return job.stepMode()!=WarEngineeringJob.Mode.BUILD||!NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snapshot,level.getBlockState(a.target().below()),owner)).isCanceled();
            }
            public void applyInventoryAndRights(){nativeMutation.applyInventoryAndRights();var proof=new CompoundTag();proof.store("Job",UUIDUtil.CODEC,job.id());proof.putInt("Cell",job.next());proof.putLong("InventoryRevision",npc.getPersonalInventory().revision());npc.getPersistentData().put(MARKER,proof);}
            public void rollback(){nativeMutation.rollback();if(oldMarker==null)npc.getPersistentData().remove(MARKER);else npc.getPersistentData().put(MARKER,oldMarker.copy());}
        };
    }
    public void notifyCommitted(NpcBase npc,WarEngineeringJob job){var at=position(job.cells().get(job.next()));npc.level().updateNeighborsAt(at,npc.level().getBlockState(at).getBlock());npc.swing(net.minecraft.world.InteractionHand.MAIN_HAND);npc.playAction(job.stepMode()==WarEngineeringJob.Mode.BUILD?com.jgeted.sagadyssey.npc.entity.NpcAction.PLACE:com.jgeted.sagadyssey.npc.entity.NpcAction.MINE,18);}
}
