package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisChunkGeneratorFailureContractTest {
    @Test
    public void structureLocateDoesNotCatchAndFallThroughToAnotherImplementation() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int locateStart = source.indexOf("public @Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure");
        int locateEnd = source.indexOf("private HolderSet<Structure> filterReachableStructures", locateStart);
        String locate = source.substring(locateStart, locateEnd);
        int reachabilityStart = source.indexOf("private Set<String> reachableStructureKeys");
        int reachabilityEnd = source.indexOf("protected MapCodec", reachabilityStart);
        String reachability = source.substring(reachabilityStart, reachabilityEnd);

        assertTrue(locate.contains("reached its safety limit"));
        assertTrue(locate.contains("unregistered structure holder"));
        assertFalse(locate.contains("catch (Throwable"));
        assertFalse(locate.contains("IrisLogging.reportError"));
        assertFalse(reachability.contains("catch (Throwable"));
        assertFalse(reachability.contains("reachable = Set.of()"));
    }

    @Test
    public void structureGenerationAbortsInsteadOfLoggingAndContinuing() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int adjustmentStart = source.indexOf("private void adjustGeneratedStructures");
        int adjustmentEnd = source.indexOf("public ChunkGeneratorStructureState createState", adjustmentStart);
        String adjustment = source.substring(adjustmentStart, adjustmentEnd);
        int placementStart = source.indexOf("private void placeVanillaStructures");
        int placementEnd = source.indexOf("private void placeVanillaStructure(", placementStart + 1);
        String placement = source.substring(placementStart, placementEnd);

        assertTrue(adjustment.contains("NativeStructureGenerationException.failure("));
        assertTrue(adjustment.contains("\"vertical adjustment\""));
        assertFalse(adjustment.contains("IrisLogging.reportError"));
        assertTrue(placement.contains("\"resolution\""));
        assertTrue(placement.contains("\"terrain integration\""));
        assertTrue(placement.contains("\"vegetation cleanup\""));
        assertTrue(placement.contains("\"placement\""));
        assertTrue(placement.contains("because structure generation is disabled outside the pack"));
        assertTrue(placement.contains("prepareSurfaceStructures"));
        assertTrue(placement.contains("clearIntersectingVegetation"));
        assertTrue(placement.indexOf("clearIntersectingVegetation")
                < placement.indexOf("prepareSurfaceStructures"));
        assertTrue(placement.indexOf("prepareSurfaceStructures")
                < placement.indexOf("for (NativePlacementGroup group"));
        assertFalse(placement.contains("IrisLogging.reportError"));
    }

    @Test
    public void heightmapRuntimeReadsAreGenerationLeased() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));

        assertTrue(source.contains("engine.acquireGenerationLease(\"bukkit_nms_heightmaps\")"));
        assertTrue(source.contains("engine.acquireGenerationLease(\"bukkit_nms_base_height\")"));
        assertTrue(source.contains("engine.acquireGenerationLease(\"bukkit_nms_base_column\")"));
        assertTrue(source.contains("catch (GenerationSessionException e)"));
    }

    @Test
    public void structureReferenceRepairIsGenerationLeased() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int referencesStart = source.indexOf("public void createReferences");
        int referencesEnd = source.indexOf("public CompletableFuture<ChunkAccess> createBiomes", referencesStart);
        String references = source.substring(referencesStart, referencesEnd);

        assertTrue(references.contains("requireGenerationLease(\"bukkit_nms_create_references\")"));
        assertTrue(references.contains("IrisContext.open(engine, lease.sessionId(), null)"));
        assertTrue(references.contains("NativeStructureReferenceRepair.createReferences("));
        assertFalse(references.contains("delegate.createReferences("));
        assertFalse(references.contains("catch ("));
    }

    @Test
    public void terrainWritesPrimeTheWorldgenHeightmapsForEveryChunk() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int fillStart = source.indexOf("public CompletableFuture<ChunkAccess> fillFromNoise");
        int fillEnd = source.indexOf("public WeightedList<MobSpawnSettings.SpawnerData> getMobsAt", fillStart);
        String fill = source.substring(fillStart, fillEnd);
        int decorationStart = source.indexOf("public void addVanillaDecorations");
        int decorationEnd = source.indexOf("public void spawnOriginalMobs", decorationStart);
        String decorations = source.substring(decorationStart, decorationEnd);
        int placementStart = source.indexOf("private void placeVanillaStructures");
        int placementEnd = source.indexOf("private static String nativeStructureBatchContext", placementStart);
        String placement = source.substring(placementStart, placementEnd);

        assertTrue(fill.contains("thenApply"));
        assertTrue(fill.contains("primeWorldgenHeightmaps"));
        assertTrue(decorations.contains("primeWorldgenHeightmaps"));
        assertFalse(decorations.contains("Heightmap.Types.WORLD_SURFACE_WG"));
        assertFalse(decorations.contains("Heightmap.Types.OCEAN_FLOOR_WG"));
        assertEquals(1, occurrences(source, "WorldgenTerrainHeightmaps.primeTerrain("));
        assertTrue(placement.contains("WorldgenTerrainHeightmaps.primeStructurePlacement("));
        assertTrue(placement.indexOf("WorldgenTerrainHeightmaps.primeStructurePlacement(")
                < placement.indexOf("prepareSurfaceStructures"));
        assertTrue(source.contains("engine.acquireGenerationLease(\"bukkit_nms_worldgen_heightmaps\")"));
        assertTrue(source.contains("int minY = chunk.getMinY() + 1;"));
    }

    @Test
    public void worldgenHeightmapPrimingLivesInTheSharedNativegenSources() throws IOException {
        Path nativegen = Path.of(System.getProperty("iris.nativeStructurePostProcessorSource")).getParent();
        Path shared = nativegen.resolve("WorldgenTerrainHeightmaps.java");

        assertTrue("Worldgen heightmap priming must be shared with the modded loaders through "
                + nativegen, Files.isRegularFile(shared));

        String heightmaps = Files.readString(shared);

        assertTrue(heightmaps.contains("package art.arcane.iris.nativegen;"));
        assertTrue(heightmaps.contains("public static void primeTerrain("));
        assertTrue(heightmaps.contains("public static void primeStructurePlacement("));
        assertFalse(heightmaps.contains("org.bukkit"));
        assertFalse(heightmaps.contains("craftbukkit"));

        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));

        assertTrue(source.contains("import art.arcane.iris.nativegen.WorldgenTerrainHeightmaps;"));
    }

    @Test
    public void onlySidecarOwnedInjectedStartsUsePersistedPlacementPolicy() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int placementStart = source.indexOf("private void placeVanillaStructures");
        int placementEnd = source.indexOf("private static String nativeStructureBatchContext", placementStart);
        String placement = source.substring(placementStart, placementEnd);
        int ownershipResolution = placement.indexOf(
                "NativeStructureOwnershipRecovery.resolve(");
        int persistedDecision = placement.indexOf("ownership.restoredDecision()", ownershipResolution);
        int adjustmentStart = source.indexOf("private void adjustGeneratedStructures");
        int adjustmentEnd = source.indexOf("public ChunkGeneratorStructureState createState", adjustmentStart);
        String adjustment = source.substring(adjustmentStart, adjustmentEnd);
        Path nativegen = Path.of(System.getProperty("iris.nativeStructurePostProcessorSource")).getParent();
        String factory = Files.readString(nativegen.resolve("NativeStructureFactory.java"));
        String injector = Files.readString(nativegen.resolve("NativeStructureStartInjector.java"));
        String recovery = Files.readString(nativegen.resolve("NativeStructureOwnershipRecovery.java"));
        int ownershipRecord = injector.indexOf("NativeStructureOwnershipStore.record(");
        int startPublication = injector.indexOf(
                "context.structureManager().setStartForStructure(", ownershipRecord);

        assertTrue(ownershipResolution >= 0);
        assertTrue(persistedDecision > ownershipResolution);
        assertTrue(recovery.contains("NativeStructureOwnershipStore.findPersisted("));
        assertTrue(recovery.contains("NativeStructureOwnershipStore.record("));
        assertTrue(ownershipRecord >= 0);
        assertTrue(startPublication > ownershipRecord);
        assertTrue(factory.contains("NativeStructureReferenceEnvelope.wrapForPublication("));
        assertTrue(adjustment.contains("NativeStructureReferenceEnvelope.wrapForPublication("));
        assertFalse(source.contains("wrapManaged("));
        assertFalse(source.contains("isIrisManagedStart("));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    public void vanillaChunkGenerationMobsUseTheVisibleBiomesVanillaDerivative() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsChunkGeneratorSource")));
        int spawnStart = source.indexOf("public void spawnOriginalMobs");
        int spawnEnd = source.indexOf("private static WeightedList", spawnStart);
        String spawn = source.substring(spawnStart, spawnEnd);

        assertTrue(spawn.contains("customBiomeSource.getVanillaSpawnBiome(visibleBiome)"));
        assertTrue(spawn.contains("NaturalSpawner.spawnMobsForChunkGeneration("));
        assertTrue(spawn.contains("region.getBiome(center.getWorldPosition().atY(region.getMaxY()))"));
        assertTrue(spawn.contains("new LegacyRandomSource(RandomSupport.generateUniqueSeed())"));
        assertTrue(spawn.contains("random.setDecorationSeed(region.getSeed()"));
        assertFalse(spawn.contains("delegate.spawnOriginalMobs"));
    }
}
