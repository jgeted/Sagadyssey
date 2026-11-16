package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.brain.fieldwork.*;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.domain.WarEngineeringJob;
import com.jgeted.sagadyssey.social.war.domain.WarEngineeringLayouts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native engineering boundary, assembled once with the selected Minecraft binding. */
public interface WarEngineeringAdapter {
    record Inspection(Optional<WarEngineeringJob.Cell> cell,boolean alreadyDone,String reason){}
    record Material(WarKey key, String label) {}
    List<Material> buildMaterials();
    List<WarEngineeringJob.Cell> programCells(NpcBase worker,WarEngineeringLayouts.Layout layout);
    Inspection inspect(NpcBase worker,WarEngineeringJob job);
    boolean occupiesWorkSurface(NpcBase worker,WarEngineeringJob job);
    String markerKey();
    BlockPos position(WarEngineeringJob.Cell cell);
    BlockState state(ServerLevel level,String encoded);
    List<WarEngineeringJob.Cell> preview(ServerPlayer owner,NpcBase worker,WarIntent.Position from,
            WarIntent.Position to,WarEngineeringJob.Mode mode,Optional<WarKey> material);
    int workTicks(NpcBase worker,BlockState block,BlockPos at);
    boolean loaded(ServerLevel level,BlockPos at);
    boolean permission(ServerPlayer owner,ServerLevel level,BlockPos at);
    boolean reachable(NpcBase worker,WarEngineeringJob.Cell cell);
    boolean occupiesTarget(NpcBase worker,WarEngineeringJob.Cell cell);
    boolean centerOnStand(NpcBase worker,BlockPos stand);
    boolean workableFrom(NpcBase worker,BlockPos feet,WarEngineeringJob.Cell cell);
    FieldworkAction action(WarEngineeringJob job);
    boolean receiptMatches(WarEngineeringJob job,int cell,FieldworkActionReceipt receipt);
    boolean marker(NpcBase worker,WarEngineeringJob job,int cell);
    FieldworkCommitService.Mutation mutation(NpcBase worker,ServerPlayer owner,WarEngineeringJob job,BooleanSupplier authority);
    void notifyCommitted(NpcBase worker,WarEngineeringJob job);
}
