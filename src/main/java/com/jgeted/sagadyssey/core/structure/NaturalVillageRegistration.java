package com.jgeted.sagadyssey.core.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import com.jgeted.sagadyssey.social.history.WorldHistoryData;
import com.jgeted.sagadyssey.social.settlement.SettlementRegistryData;

/** Carries the actual structure identity only while vanilla places one of its pieces. */
public final class NaturalVillageRegistration {
    public static final String ELIGIBILITY_TAG = "SagadysseyNaturalRegistration";
    private static final ThreadLocal<Placement> CURRENT = new ThreadLocal<>();
    private record Placement(PoolElementStructurePiece piece, NaturalVillageBuilding building) {}

    /** Implemented on StructureStart so old unmarked disk starts remain ineligible. */
    public interface Eligibility {
        void sagadyssey$setRegistrationEligible(boolean eligible);
    }

    private NaturalVillageRegistration() {}

    public static void duringPiece(StructureStart start, StructurePiece piece, WorldGenLevel level,
                                   Runnable vanillaPlacement) {
        var id = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
        if (!NaturalVillageBuilding.SUPPORTED.equals(id)
                || !(piece instanceof PoolElementStructurePiece poolPiece)
                || poolPiece.getElement().getClass() != SinglePoolElement.class) {
            vanillaPlacement.run();
            return;
        }
        // Decode the actual element's resource identity, never infer it from a pool name or toString().
        var encoded = SinglePoolElement.CODEC.codec().encodeStart(
                level.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                (SinglePoolElement) poolPiece.getElement()).result();
        if (encoded.isEmpty() || !(encoded.get() instanceof CompoundTag tag)
                || !NaturalVillageLayout.supportedTemplate(tag.getStringOr("location", ""))) {
            vanillaPlacement.run();
            return;
        }
        var anchor = start.getChunkPos().getWorldPosition();
        var center = new BlockPos(anchor.getX() + 8, start.getPieces().getFirst().getBoundingBox().minY(), anchor.getZ() + 8);
        var building = new NaturalVillageBuilding(level.getLevel().dimension().identifier().toString(),
                id.toString(), anchor, center, tag.getStringOr("location", ""),
                poolPiece.getPosition(), poolPiece.getRotation());
        Placement previous = CURRENT.get();
        CURRENT.set(new Placement(poolPiece, building));
        try {
            vanillaPlacement.run();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static void placed(PoolElementStructurePiece piece, WorldGenLevel level,
                              BoundingBox currentChunkBounds, boolean succeeded) {
        Placement placement = CURRENT.get();
        // Only the successful callback covering the template origin confirms this building.
        // Other intersecting chunks, candidates, failed placements and direct /place calls do not.
        if (!succeeded || placement == null || placement.piece() != piece
                || !currentChunkBounds.isInside(placement.building().buildingOrigin())) return;
        var server = level.getLevel().getServer();
        var building = placement.building();
        long time = level.getLevel().getGameTime();
        Runnable register = () -> {
            NaturalVillageLifeData.get(server).schedule(building);
            if (SettlementRegistryData.get(server).registerPlacedVillage(building, time)) {
                WorldHistoryData.get(server).append(time, "natural_village_building_registered",
                        building.settlementId(), "边境村落", building.buildingKey(), 30);
            }
        };
        if (server.isSameThread()) register.run(); else server.execute(register);
    }
}
