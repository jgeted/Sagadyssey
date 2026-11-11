package com.jgeted.sagadyssey.core.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** One physical building inside one natural structure instance, not one chunk callback. */
public record NaturalVillageBuilding(String dimension, String structureId, BlockPos startChunkOrigin,
                                     BlockPos settlementCenter, String templateId,
                                     BlockPos buildingOrigin, Rotation rotation) {
    public static final Identifier SUPPORTED = Identifier.parse("sagadyssey:vilg_2");

    public NaturalVillageBuilding {
        Identifier.parse(dimension);
        if (!SUPPORTED.toString().equals(structureId) || !NaturalVillageLayout.supportedTemplate(templateId)) {
            throw new IllegalArgumentException("Only the explicit vilg_2 structure/building mapping is supported");
        }
        startChunkOrigin = startChunkOrigin.immutable();
        if (startChunkOrigin.getY() != 0 || (startChunkOrigin.getX() & 15) != 0
                || (startChunkOrigin.getZ() & 15) != 0) {
            throw new IllegalArgumentException("Structure identity uses its start chunk minimum X/Z and Y=0");
        }
        settlementCenter = settlementCenter.immutable();
        buildingOrigin = buildingOrigin.immutable();
        Objects.requireNonNull(rotation);
    }

    private String structureKey() {
        return dimension + "|" + structureId + "|" + startChunkOrigin.asLong();
    }

    public UUID settlementId() {
        return UUID.nameUUIDFromBytes(("natural-village:v1|" + structureKey()).getBytes(StandardCharsets.UTF_8));
    }

    public String buildingKey() {
        return "natural-building:v1|" + structureKey() + "|" + templateId
                + "|" + buildingOrigin.asLong() + "|" + rotation.name();
    }
}
