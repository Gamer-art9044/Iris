/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.modded;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.framework.NativeStructurePlacementPlanner;
import art.arcane.iris.engine.framework.NativeStructureStartPlan;
import art.arcane.iris.engine.object.IrisMaterialPalette;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.nativegen.NativeStructureGenerationException;
import art.arcane.iris.nativegen.NativeStructurePostProcessor;
import art.arcane.iris.nativegen.NativeStructureReferenceEnvelope;
import art.arcane.iris.nativegen.NativeStructureSurfaceFitter;
import art.arcane.iris.nativegen.NativeStructureTerrainIntegrator;
import art.arcane.iris.nativegen.NativeStructureVegetationClearer;
import art.arcane.iris.nativegen.NativeStructureVerticalPlacer;
import art.arcane.iris.nativegen.WorldgenTerrainHeightmaps;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.math.RNG;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;

/**
 * Native (vanilla registry) structure stage for {@link IrisModdedChunkGenerator}. The generator keeps the
 * {@link net.minecraft.world.level.chunk.ChunkGenerator} overrides because they issue {@code super} calls;
 * everything they do beyond that lives here.
 */
final class ModdedNativeStructureStage {
    private static final int WORLD_CHECK_SHIFT_RECORD_LIMIT = 4096;
    private static final boolean WORLD_CHECK_ENABLED = Boolean.getBoolean("iris.worldcheck");

    private final IrisModdedChunkGenerator generator;
    private final ConcurrentHashMap<NativeStructureStartKey, Integer> worldCheckStructureShifts = new ConcurrentHashMap<>();
    private volatile StructureStepCache structureStepCache;

    ModdedNativeStructureStage(IrisModdedChunkGenerator generator) {
        this.generator = generator;
    }

    Pair<BlockPos, Holder<Structure>> findNearestIrisStructure(ServerLevel level,
                                                              HolderSet<Structure> holders,
                                                              BlockPos pos, int radius, boolean findUnexplored,
                                                              Engine current) {
        if (findUnexplored) {
            return null;
        }
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        BlockPos best = null;
        Holder<Structure> bestHolder = null;
        long bestDistance = Long.MAX_VALUE;
        for (Holder<Structure> holder : holders) {
            Identifier id = registry.getKey(holder.value());
            if (id == null) {
                throw new IllegalStateException("Native structure locate received an unregistered structure holder");
            }
            String structureId = id.toString();
            if (!IrisStructureLocator.isPlaced(current, structureId)) {
                continue;
            }
            IrisStructureLocator.LocateResult result = IrisStructureLocator.locate(
                    current, structureId, pos.getX(), pos.getZ(), radius);
            if (result.status() == IrisStructureLocator.LocateStatus.SEARCH_LIMIT_REACHED) {
                throw new IllegalStateException("Iris structure locate reached its safety limit for "
                        + structureId + " within " + radius + " chunks");
            }
            if (!result.found()) {
                continue;
            }
            long dx = (long) result.originX() - pos.getX();
            long dz = (long) result.originZ() - pos.getZ();
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new BlockPos(result.originX(), result.baseY(), result.originZ());
                bestHolder = holder;
            }
        }
        return best == null ? null : Pair.of(best, bestHolder);
    }

    HolderSet<Structure> filterReachableNativeStructures(ServerLevel level, HolderSet<Structure> holders,
                                                        Engine current) {
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> kept = new ArrayList<>(holders.size());
        for (Holder<Structure> holder : holders) {
            Identifier id = registry.getKey(holder.value());
            if (id == null) {
                throw new IllegalStateException("Native structure filtering received an unregistered structure holder");
            }
            String key = id.toString();
            IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(current,
                    key, NativeStructureVegetationClearer.isUndergroundStep(holder.value().step()));
            if (!decision.generate() || !generator.structureBiomeSource.isStructureReachable(holder)) {
                continue;
            }
            kept.add(holder);
        }
        return kept.size() == holders.size() ? holders : HolderSet.direct(kept);
    }

    void adjustGeneratedStructures(RegistryAccess registryAccess, ChunkAccess chunk,
                                   Map<Structure, StructureStart> previousStarts,
                                   Map<Structure, NativeStructureStartPlan> configuredStarts,
                                   Engine current,
                                   StructureTemplateManager templateManager) {
        Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
        ChunkPos chunkPos = chunk.getPos();
        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
            Structure structure = entry.getKey();
            StructureStart start = entry.getValue();
            if (!start.isValid() || previousStarts.get(structure) == start) {
                continue;
            }
            if (configuredStarts.containsKey(structure)) {
                recordWorldCheckStructureShift(
                        configuredStarts.get(structure).source().getStructure(), start.getChunkPos(), 0);
                continue;
            }
            Identifier id = registry.getKey(structure);
            String structureId = id == null ? null : id.toString();
            if (structureId == null) {
                throw NativeStructureGenerationException.failure(
                        "resolution", null, chunkPos.x(), chunkPos.z());
            }
            boolean undergroundStep = NativeStructureVegetationClearer.isUndergroundStep(structure.step());
            IrisNativeStructureDecision decision;
            try {
                decision = NativeStructureGenerationPolicy.resolve(current,
                        structureId, undergroundStep);
            } catch (Throwable error) {
                throw NativeStructureGenerationException.failure(
                        "policy resolution", structureId, chunkPos.x(), chunkPos.z(), error);
            }
            if (!decision.generate()) {
                chunk.setStartForStructure(structure, StructureStart.INVALID_START);
                continue;
            }
            int offsetY;
            try {
                offsetY = NativeStructureVerticalPlacer.applyVerticalPlacement(
                        start,
                        structureId,
                        decision.yShift(),
                        generator.getSeaLevel(),
                        chunk.getMinY(),
                        chunk.getMinY() + chunk.getHeight(),
                        undergroundStep,
                        decision.preserveSourceY(),
                        decision.yBand(),
                        (x, z) -> current.getHeight(x, z, true) + current.getMinHeight());
                StructureStart wrapped = NativeStructureReferenceEnvelope.wrap(
                        start, structure, start.getReferences(), templateManager,
                        NativeStructureTerrainIntegrator.resolveNativeTerrain(start, decision.terrain()));
                chunk.setStartForStructure(structure, wrapped);
            } catch (Throwable error) {
                throw NativeStructureGenerationException.failure(
                        "vertical adjustment", structureId, chunkPos.x(), chunkPos.z(), error);
            }
            recordWorldCheckStructureShift(structureId, start.getChunkPos(), offsetY);
        }
    }

    void placeVanillaStructures(WorldGenLevel world, ChunkAccess chunk, StructureManager structureManager) {
        if (!structureManager.shouldGenerateStructures()) {
            ChunkPos disabledChunk = chunk.getPos();
            throw new IllegalStateException("Iris cannot generate native structures in chunk "
                    + disabledChunk.x() + "," + disabledChunk.z()
                    + " because generate-structures=false disables them outside the pack; set generate-structures=true, "
                    + "restart the server, and deny individual structures through importedStructures.disabled");
        }
        ChunkPos chunkPos = chunk.getPos();
        SectionPos sectionPos = SectionPos.of(chunkPos, world.getMinSectionY());
        BlockPos origin = sectionPos.origin();
        Registry<Structure> registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<List<Structure>> byStep = structuresByStep(registry);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(world.getSeed(), origin.getX(), origin.getZ());
        BoundingBox area = writableArea(chunk);
        int steps = GenerationStep.Decoration.values().length;
        Engine current = generator.engine();
        List<NativePlacementGroup> placementGroups = new ArrayList<>();
        List<StructureStart> heightmapStarts = new ArrayList<>();
        List<StructureStart> nativeStarts = new ArrayList<>();
        List<NativeStructureVegetationClearer.VegetationTarget> vegetationTargets = new ArrayList<>();
        List<NativeStructureTerrainIntegrator.TerrainTarget> terrainTargets = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            int index = 0;
            for (Structure structure : byStep.get(step)) {
                Identifier id = registry.getKey(structure);
                String structureId = id == null ? null : id.toString();
                if (structureId == null) {
                    throw NativeStructureGenerationException.failure(
                            "resolution", null, chunkPos.x(), chunkPos.z());
                }
                try {
                    IrisNativeStructureDecision sourceDecision = NativeStructureGenerationPolicy.resolve(current,
                            structureId, NativeStructureVegetationClearer.isUndergroundStep(structure.step()));
                    List<StructureStart> starts = structureManager.startsForStructure(sectionPos, structure);
                    List<NativePlacement> resolvedPlacements = new ArrayList<>(starts.size());
                    for (StructureStart start : starts) {
                        NativeStructureStartPlan plan = NativeStructurePlacementPlanner.matchingPlan(
                                current, structureId, start.getChunkPos().x(), start.getChunkPos().z());
                        IrisNativeStructureDecision decision = plan == null
                                ? sourceDecision : NativeStructurePlacementPlanner.decisionFor(plan);
                        if (!decision.generate()) {
                            continue;
                        }
                        resolvedPlacements.add(new NativePlacement(start, decision));
                        heightmapStarts.add(start);
                        terrainTargets.add(new NativeStructureTerrainIntegrator.TerrainTarget(
                                structureId, start,
                                NativeStructureTerrainIntegrator.resolveNativeTerrain(
                                        start, decision.terrain())));
                        if (plan == null || !plan.placement().isUnderground()) {
                            nativeStarts.add(start);
                        }
                        boolean clearEntireFootprint = NativeStructureVegetationClearer
                                .shouldClearEntireVegetationFootprint(
                                        structure.step(), decision.clearVegetation());
                        vegetationTargets.add(new NativeStructureVegetationClearer.VegetationTarget(
                                start, clearEntireFootprint));
                    }
                    if (!resolvedPlacements.isEmpty()) {
                        placementGroups.add(new NativePlacementGroup(
                                structureId, index, step, List.copyOf(resolvedPlacements)));
                    }
                } catch (Throwable error) {
                    throw NativeStructureGenerationException.failure(
                            "resolution", structureId, chunkPos.x(), chunkPos.z(), error);
                }
                index++;
            }
        }
        try {
            int runtimeMinY = world.getMinY();
            WorldgenTerrainHeightmaps.primeStructurePlacement(
                    world, heightmapStarts,
                    worldgenSurfaceHeight(current, runtimeMinY),
                    worldgenFloorHeight(current, runtimeMinY));
        } catch (Throwable error) {
            throw NativeStructureGenerationException.failure(
                    "heightmap priming", nativeStructureBatchContext(placementGroups),
                    chunkPos.x(), chunkPos.z(), error);
        }
        try {
            NativeStructureSurfaceFitter.prepareSurfaceStructures(
                    world, area, nativeStarts,
                    (x, z) -> current.getHeight(x, z, true) + current.getMinHeight());
        } catch (Throwable error) {
            throw NativeStructureGenerationException.failure(
                    "terrain integration", nativeStructureBatchContext(placementGroups),
                    chunkPos.x(), chunkPos.z(), error);
        }
        try {
            NativeStructureVegetationClearer.clearIntersectingVegetation(
                    world, chunk, area, vegetationTargets);
        } catch (Throwable error) {
            throw NativeStructureGenerationException.failure(
                    "vegetation cleanup", nativeStructureBatchContext(placementGroups),
                    chunkPos.x(), chunkPos.z(), error);
        }
        try {
            NativeStructurePostProcessor.prepareTerrain(
                    world, area, terrainTargets, this::resolvePaletteBlock);
        } catch (Throwable error) {
            throw NativeStructureGenerationException.failure(
                    "terrain carving", nativeStructureBatchContext(placementGroups),
                    chunkPos.x(), chunkPos.z(), error);
        }
        for (NativePlacementGroup group : placementGroups) {
            random.setFeatureSeed(decorationSeed, group.featureIndex(), group.step());
            try {
                for (NativePlacement placement : group.placements()) {
                    placeVanillaStructure(world, structureManager, random, area, chunkPos,
                            group.structureId(), placement.start(), placement.decision());
                }
            } catch (Throwable error) {
                throw NativeStructureGenerationException.failure(
                        "placement", group.structureId(), chunkPos.x(), chunkPos.z(), error);
            }
        }
    }

    private static String nativeStructureBatchContext(List<NativePlacementGroup> placementGroups) {
        if (placementGroups.isEmpty()) {
            return "<no resolved native structures>";
        }
        StringBuilder context = new StringBuilder("[");
        for (int i = 0; i < placementGroups.size(); i++) {
            if (i > 0) {
                context.append(", ");
            }
            context.append(placementGroups.get(i).structureId());
        }
        return context.append(']').toString();
    }

    private void placeVanillaStructure(WorldGenLevel world, StructureManager structureManager,
                                       WorldgenRandom random, BoundingBox area, ChunkPos chunkPos,
                                       String structureId, StructureStart start,
                                       IrisNativeStructureDecision decision) {
        NativeStructurePostProcessor.place(world, structureManager, generator, random, area, chunkPos,
                structureId, start, decision, this::resolvePaletteBlock,
                (x, z) -> generator.engine().getHeight(x, z, true) + generator.engine().getMinHeight());
    }

    private List<List<Structure>> structuresByStep(Registry<Structure> registry) {
        StructureStepCache cached = structureStepCache;
        if (cached != null && cached.registry() == registry) {
            return cached.structures();
        }
        synchronized (generator) {
            cached = structureStepCache;
            if (cached != null && cached.registry() == registry) {
                return cached.structures();
            }
            int steps = GenerationStep.Decoration.values().length;
            List<List<Structure>> grouped = new ArrayList<>(steps);
            for (int step = 0; step < steps; step++) {
                grouped.add(new ArrayList<>());
            }
            for (Structure structure : registry) {
                grouped.get(structure.step().ordinal()).add(structure);
            }
            for (int step = 0; step < steps; step++) {
                grouped.set(step, List.copyOf(grouped.get(step)));
            }
            List<List<Structure>> resolved = List.copyOf(grouped);
            structureStepCache = new StructureStepCache(registry, resolved);
            return resolved;
        }
    }

    private void recordWorldCheckStructureShift(String structureId, ChunkPos startChunk, int offsetY) {
        if (!WORLD_CHECK_ENABLED || structureId == null) {
            return;
        }
        if (worldCheckStructureShifts.size() >= WORLD_CHECK_SHIFT_RECORD_LIMIT) {
            worldCheckStructureShifts.clear();
        }
        worldCheckStructureShifts.put(new NativeStructureStartKey(structureId, startChunk.pack()), offsetY);
    }

    Integer worldCheckStructureShift(String structureId, ChunkPos startChunk) {
        if (structureId == null || startChunk == null) {
            return null;
        }
        return worldCheckStructureShifts.get(new NativeStructureStartKey(structureId, startChunk.pack()));
    }

    void clearWorldCheckStructureShifts() {
        worldCheckStructureShifts.clear();
    }

    private BlockState resolvePaletteBlock(IrisMaterialPalette palette, RNG rng,
                                          int x, int y, int z) {
        PlatformBlockState platformState = palette.get(rng, x, y, z, generator.engine().getData());
        if (platformState == null || !(platformState.nativeHandle() instanceof BlockState blockState)) {
            throw new IllegalStateException("Configured native structure palette did not resolve a Minecraft block at "
                    + x + "," + y + "," + z);
        }
        return blockState;
    }

    private BoundingBox writableArea(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        int minY = chunk.getMinY();
        int maxY = minY + chunk.getHeight() - 1;
        return new BoundingBox(minX, minY, minZ, minX + 15, maxY, minZ + 15);
    }

    private IntBinaryOperator worldgenSurfaceHeight(Engine generationEngine, int runtimeMinY) {
        return (x, z) -> generationEngine.getHeight(x, z, false) + runtimeMinY + 1;
    }

    private IntBinaryOperator worldgenFloorHeight(Engine generationEngine, int runtimeMinY) {
        return (x, z) -> generationEngine.getHeight(x, z, true) + runtimeMinY + 1;
    }

    private record NativeStructureStartKey(String structureId, long chunkPosition) {
    }

    private record NativePlacement(StructureStart start, IrisNativeStructureDecision decision) {
    }

    private record NativePlacementGroup(String structureId, int featureIndex, int step,
                                        List<NativePlacement> placements) {
    }

    private record StructureStepCache(Registry<Structure> registry, List<List<Structure>> structures) {
    }
}
