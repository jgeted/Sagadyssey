package com.jgeted.sagadyssey.core.command;

import com.jgeted.sagadyssey.core.MinecraftApi;

import com.jgeted.sagadyssey.Sagadyssey;
import com.jgeted.sagadyssey.core.structure.SagadysseyStructures;
import com.jgeted.sagadyssey.core.structure.StructureConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Places one comparison row or a complete gallery of structure variants. */
public final class StructurePreviewCommand {
    private static final List<Variant> VARIANTS = List.of(
            new Variant("原版", ""),
            new Variant("白桦", "_birch"),
            new Variant("云杉", "_spruce"),
            new Variant("金合欢", "_acacia")
    );
    private static final int PLAYER_CLEARANCE = 8;
    private static final int BUILDING_GAP = 6;
    private static final int ROW_GAP = 10;
    private static final Set<String> GALLERY_ORIGINAL_ONLY = Set.of(
            "wallgate"
    );

    private StructurePreviewCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("saga")
                .then(Commands.literal("structure")
                        .then(Commands.literal("preview")
                                .requires(source -> MinecraftApi.hasPermission(source, 2))
                                .then(Commands.argument("structure_id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestStructures(builder))
                                        .executes(ctx -> preview(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "structure_id")))))
                        .then(Commands.literal("gallery")
                                .requires(source -> MinecraftApi.hasPermission(source, 2))
                                .executes(ctx -> gallery(ctx.getSource(), GalleryGroup.ALL))
                                .then(Commands.literal("small")
                                        .executes(ctx -> gallery(ctx.getSource(), GalleryGroup.SMALL)))
                                .then(Commands.literal("large")
                                        .executes(ctx -> gallery(ctx.getSource(), GalleryGroup.LARGE)))
                                .then(Commands.literal("all")
                                        .executes(ctx -> gallery(ctx.getSource(), GalleryGroup.ALL))))));
    }

    private static CompletableFuture<Suggestions> suggestStructures(SuggestionsBuilder builder) {
        SagadysseyStructures.getAllIds().stream().sorted().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static int preview(CommandSourceStack source, String structureId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        StructureConfig config = SagadysseyStructures.getConfig(structureId);
        if (config == null) {
            source.sendFailure(Component.literal("未知 Sagadyssey 建筑：" + structureId));
            return 0;
        }

        ServerLevel level = player.level();
        String baseTemplatePath = config.getNbtFileName();
        List<LoadedTemplate> templates = loadTemplates(level, baseTemplatePath);
        if (templates.isEmpty() || !templates.getFirst().variant().suffix().isEmpty()) {
            source.sendFailure(Component.literal("找不到原版建筑模板：sagadyssey:" + baseTemplatePath));
            return 0;
        }

        StructurePlaceSettings measuringSettings = placementSettings();
        BoundingBox localBox = templates.getFirst().template().getBoundingBox(measuringSettings, BlockPos.ZERO);
        Direction forward = player.getDirection();
        Direction right = forward.getClockWise();
        int rowWidth = horizontalSpan(localBox, right);
        int rowDepth = horizontalSpan(localBox, forward);
        int step = rowWidth + BUILDING_GAP;
        int forwardDistance = PLAYER_CLEARANCE + (rowDepth + 1) / 2;

        int placed = 0;
        List<String> results = new ArrayList<>();
        for (int index = 0; index < templates.size(); index++) {
            LoadedTemplate loaded = templates.get(index);
            int sideOffset = index * step - ((templates.size() - 1) * step) / 2;
            BlockPos targetCenter = player.blockPosition()
                    .relative(forward, forwardDistance)
                    .relative(right, sideOffset);
            BlockPos origin = originAtSurface(level, targetCenter, localBox);
            StructurePlaceSettings settings = placementSettings();
            BoundingBox worldBox = loaded.template().getBoundingBox(settings, origin);
            loadChunks(level, worldBox);

            boolean success = loaded.template().placeInWorld(
                    level,
                    origin,
                    origin,
                    settings,
                    RandomSource.create(level.getGameTime() + loaded.location().hashCode()),
                    2
            );
            if (!success) {
                source.sendFailure(Component.literal("模板未放置任何方块：" + loaded.location()));
                continue;
            }

            placed++;
            results.add(loaded.variant().displayName() + "=" + origin.toShortString());
        }

        if (placed == 0) return 0;
        int placedCount = placed;
        source.sendSuccess(() -> Component.literal("§a已生成 " + structureId + " 的 "
                + placedCount + " 个对比模板。"), true);
        source.sendSuccess(() -> Component.literal("§7从左到右：" + String.join("，", results)), false);
        if (templates.size() == 1) {
            source.sendSuccess(() -> Component.literal("§e该建筑没有可用的木材变体，因此只放置了原版。"), false);
        }
        return placed;
    }

    private static int gallery(CommandSourceStack source, GalleryGroup group)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();
        List<GalleryRow> rows = loadGalleryRows(level, group);
        if (rows.isEmpty()) {
            source.sendFailure(Component.literal("没有可用于展示的建筑模板。"));
            return 0;
        }

        Direction forward = player.getDirection();
        Direction right = forward.getClockWise();
        int maxRowWidth = rows.stream()
                .mapToInt(row -> horizontalSpan(row.localBox(), right))
                .max()
                .orElse(1);
        int columnStep = maxRowWidth + BUILDING_GAP;
        int forwardCursor = PLAYER_CLEARANCE;
        int placed = 0;
        List<String> rowLabels = new ArrayList<>();

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            GalleryRow row = rows.get(rowIndex);
            int rowDepth = horizontalSpan(row.localBox(), forward);
            int rowCenterDistance = forwardCursor + (rowDepth + 1) / 2;
            int rowPlaced = 0;

            for (LoadedTemplate loaded : row.templates()) {
                int column = VARIANTS.indexOf(loaded.variant());
                if (column < 0) continue;
                int sideOffset = column * columnStep - ((VARIANTS.size() - 1) * columnStep) / 2;
                BlockPos targetCenter = player.blockPosition()
                        .relative(forward, rowCenterDistance)
                        .relative(right, sideOffset);
                BlockPos origin = originAtSurface(level, targetCenter, row.localBox());
                StructurePlaceSettings settings = placementSettings();
                BoundingBox worldBox = loaded.template().getBoundingBox(settings, origin);
                loadChunks(level, worldBox);
                boolean success = loaded.template().placeInWorld(
                        level,
                        origin,
                        origin,
                        settings,
                        RandomSource.create(level.getGameTime() + loaded.location().hashCode()),
                        2
                );
                if (success) {
                    placed++;
                    rowPlaced++;
                } else {
                    source.sendFailure(Component.literal("模板未放置任何方块：" + loaded.location()));
                }
            }

            rowLabels.add((rowIndex + 1) + "=" + row.structureId()
                    + "(" + rowPlaced + "个)");
            forwardCursor += rowDepth + ROW_GAP;
        }

        if (placed == 0) return 0;
        int placedCount = placed;
        source.sendSuccess(() -> Component.literal("§a" + group.displayName() + "建筑展区生成完成："
                + rows.size() + " 排，共 " + placedCount + " 个模板。"), true);
        source.sendSuccess(() -> Component.literal("§7每排从左到右：原版 → 白桦 → 云杉 → 金合欢"), false);
        source.sendSuccess(() -> Component.literal("§7排号：" + String.join("，", rowLabels)), false);
        return placed;
    }

    private static List<GalleryRow> loadGalleryRows(ServerLevel level, GalleryGroup group) {
        List<GalleryRow> rows = new ArrayList<>();
        StructurePlaceSettings settings = placementSettings();
        for (String structureId : SagadysseyStructures.getAllIds().stream().sorted().toList()) {
            StructureConfig config = SagadysseyStructures.getConfig(structureId);
            if (config == null || !group.includes(config)) continue;
            List<LoadedTemplate> templates = loadTemplates(level, config.getNbtFileName());
            if (templates.isEmpty() || !templates.getFirst().variant().suffix().isEmpty()) continue;
            LoadedTemplate original = templates.getFirst();
            BoundingBox localBox = original.template().getBoundingBox(settings, BlockPos.ZERO);
            if (GALLERY_ORIGINAL_ONLY.contains(structureId)) {
                templates = List.of(original);
            }
            rows.add(new GalleryRow(structureId, templates, localBox));
        }
        return rows;
    }

    private static List<LoadedTemplate> loadTemplates(ServerLevel level, String baseTemplatePath) {
        List<LoadedTemplate> templates = new ArrayList<>();
        for (Variant variant : VARIANTS) {
            Identifier location = Identifier.fromNamespaceAndPath(
                    Sagadyssey.MOD_ID, baseTemplatePath + variant.suffix());
            Optional<StructureTemplate> template = level.getStructureManager().get(location);
            template.ifPresent(value -> templates.add(new LoadedTemplate(variant, location, value)));
        }
        return templates;
    }

    private static StructurePlaceSettings placementSettings() {
        return new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_AND_AIR);
    }

    private static int horizontalSpan(BoundingBox box, Direction direction) {
        return direction.getAxis() == Direction.Axis.X
                ? box.maxX() - box.minX() + 1
                : box.maxZ() - box.minZ() + 1;
    }

    private static BlockPos originAtSurface(ServerLevel level, BlockPos center, BoundingBox localBox) {
        int centerOffsetX = Math.floorDiv(localBox.minX() + localBox.maxX(), 2);
        int centerOffsetZ = Math.floorDiv(localBox.minZ() + localBox.maxZ(), 2);
        int surfaceY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        return new BlockPos(
                center.getX() - centerOffsetX,
                surfaceY - localBox.minY(),
                center.getZ() - centerOffsetZ
        );
    }

    private static void loadChunks(ServerLevel level, BoundingBox box) {
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private record Variant(String displayName, String suffix) { }

    private record LoadedTemplate(Variant variant, Identifier location, StructureTemplate template) { }

    private record GalleryRow(String structureId, List<LoadedTemplate> templates, BoundingBox localBox) { }

    private enum GalleryGroup {
        ALL("全部"),
        SMALL("中小型"),
        LARGE("大型");

        private final String displayName;

        GalleryGroup(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }

        private boolean includes(StructureConfig config) {
            boolean large = "large".equalsIgnoreCase(config.getSize());
            return this == ALL || (this == LARGE) == large;
        }
    }
}
