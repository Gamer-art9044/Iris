package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.core.datapack.DatapackIngestService;
import art.arcane.iris.core.datapack.DatapackStructureScopeIndex;
import com.mojang.datafixers.util.Either;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.junit.Test;
import org.junit.BeforeClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NMSBindingDatapackStructureScopeTest {
    private static final String SOURCE = "https://example.test/managed.zip";

    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void managedSetContainingVanillaStructureIsAbsentOutsideDeclaringDimension() {
        Holder<Structure> vanillaStructure = structureHolder("minecraft:pillager_outpost");
        Holder<StructureSet> managedSet = structureSetHolder(
                "managed:illager_barracks", vanillaStructure);
        DatapackStructureScopeIndex index = index(
                List.of(),
                List.of("managed:illager_barracks"));

        DatapackStructureStateFilter.Selection vanilla = DatapackStructureStateFilter.filter(
                List.of(managedSet), index, Set.of());
        DatapackStructureStateFilter.Selection declaring = DatapackStructureStateFilter.filter(
                List.of(managedSet), index, index.declaredSources(List.of(SOURCE)));

        assertEquals(0, vanilla.structureSets().size());
        assertEquals(1, vanilla.excludedManagedSets());
        assertEquals(1, declaring.structureSets().size());
        assertSame(managedSet, declaring.structureSets().getFirst());
    }

    @Test
    public void unmanagedSetRetainsOnlyDefinitionsAllowedInTheWorld() {
        Holder<Structure> vanillaStructure = structureHolder("minecraft:village_plains");
        Holder<Structure> managedStructure = structureHolder("managed:tavern");
        Holder<StructureSet> vanillaSet = structureSetHolder(
                "minecraft:villages", vanillaStructure, managedStructure);
        DatapackStructureScopeIndex index = index(
                List.of("managed:tavern"),
                List.of());

        DatapackStructureStateFilter.Selection vanilla = DatapackStructureStateFilter.filter(
                List.of(vanillaSet), index, Set.of());
        DatapackStructureStateFilter.Selection declaring = DatapackStructureStateFilter.filter(
                List.of(vanillaSet), index, index.declaredSources(List.of(SOURCE)));

        assertEquals(1, vanilla.structureSets().size());
        assertEquals(1, vanilla.structureSets().getFirst().value().structures().size());
        assertSame(vanillaStructure,
                vanilla.structureSets().getFirst().value().structures().getFirst().structure());
        assertSame(vanillaSet.value().placement(),
                vanilla.structureSets().getFirst().value().placement());
        assertSame(vanillaSet, declaring.structureSets().getFirst());
    }

    @Test
    public void setWithNoAllowedDefinitionsIsRemoved() {
        Holder<StructureSet> unmanagedSet = structureSetHolder(
                "minecraft:custom", structureHolder("managed:only"));
        DatapackStructureScopeIndex index = index(List.of("managed:only"), List.of());

        DatapackStructureStateFilter.Selection selection = DatapackStructureStateFilter.filter(
                List.of(unmanagedSet), index, Set.of());

        assertEquals(0, selection.structureSets().size());
    }

    @Test
    public void spigotDirectHolderRetainsItsStructureSetKey() {
        ResourceKey<StructureSet> key = ResourceKey.create(
                Registries.STRUCTURE_SET,
                Identifier.parse("minecraft:villages"));
        ChunkGeneratorStructureState.KeyedRandomSpreadStructurePlacement placement =
                new ChunkGeneratorStructureState.KeyedRandomSpreadStructurePlacement(
                        key,
                        Vec3i.ZERO,
                        StructurePlacement.FrequencyReductionMethod.DEFAULT,
                        1.0F,
                        10387312,
                        Optional.empty(),
                        34,
                        8,
                        RandomSpreadType.LINEAR);
        StructureSet structureSet = new StructureSet(
                List.of(new StructureSet.StructureSelectionEntry(
                        structureHolder("minecraft:village_plains"), 1)),
                placement);

        assertEquals("minecraft:villages",
                DatapackStructureStateFilter.structureSetKey(Holder.direct(structureSet)));
    }

    @Test
    public void excludedManagedSetCannotSuppressAnAllowedSetThroughExclusionZone() {
        Holder<StructureSet> managedSet = structureSetHolder(
                "managed:blocked",
                structureHolder("managed:blocked"));
        RandomSpreadStructurePlacement originalPlacement = new RandomSpreadStructurePlacement(
                Vec3i.ZERO,
                StructurePlacement.FrequencyReductionMethod.DEFAULT,
                1.0F,
                4567,
                Optional.of(new StructurePlacement.ExclusionZone(managedSet, 1)),
                32,
                8,
                RandomSpreadType.LINEAR);
        Holder<StructureSet> vanillaSet = structureSetHolder(
                "minecraft:allowed",
                originalPlacement,
                structureHolder("minecraft:village_plains"));
        DatapackStructureScopeIndex index = index(
                List.of("managed:blocked"),
                List.of("managed:blocked"));

        DatapackStructureStateFilter.Selection selection = DatapackStructureStateFilter.filter(
                List.of(vanillaSet, managedSet), index, Set.of());

        assertEquals(1, selection.structureSets().size());
        StructurePlacement scopedPlacement = selection.structureSets().getFirst().value().placement();
        assertEquals(0, DatapackStructureStateFilter.exclusionZone(scopedPlacement).stream().count());
    }

    @Test
    public void standardPublishesOneDeferredStateWhileJigsawPublishesInitializedEmptyState() throws IOException {
        Path chunkGeneratorSource = Path.of(System.getProperty("iris.nmsChunkGeneratorSource"));
        String source = Files.readString(chunkGeneratorSource.resolveSibling("NMSBinding.java"));
        int methodStart = source.indexOf("public DatapackStructureScopeResult scopeDatapackStructures(");
        int methodEnd = source.indexOf("\n    @Override\n    public void completeStudioStructureBootstrap", methodStart);

        assertTrue(methodStart >= 0);
        assertTrue(methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);
        int filteredState = method.indexOf("possibleSetsField.set(scopedState, selection.structureSets());");
        int jigsawMode = method.indexOf(
                "boolean jigsawStudio = platformGenerator != null");
        int jigsawOnly = method.indexOf("if (jigsawStudio)");
        int emptyCreation = method.indexOf("ChunkGeneratorStructureState bootstrapState = createStructureState(");
        int emptyFiltering = method.indexOf("bootstrapSetsField.set(bootstrapState, List.of());");
        int emptyInitialization = method.indexOf("bootstrapState.ensureStructuresGenerated();");
        int emptyPublication = method.indexOf("stateField.set(chunkMap, bootstrapState);");
        int standardOnly = method.indexOf("else if (studioBootstrap)");
        int retention = method.indexOf("irisGenerator.retainStudioStructureState(");
        int standardPublication = method.indexOf("stateField.set(chunkMap, scopedState);");
        int immediateInitialization = method.indexOf("initializeAndPublishStructureState(");

        assertTrue(filteredState >= 0);
        assertTrue(jigsawMode > filteredState);
        assertTrue(jigsawOnly > jigsawMode);
        assertTrue(emptyCreation > jigsawOnly);
        assertTrue(emptyFiltering > emptyCreation);
        assertTrue(emptyInitialization > emptyFiltering);
        assertTrue(emptyPublication > emptyInitialization);
        assertTrue(standardOnly > emptyPublication);
        assertTrue(retention > standardOnly);
        assertTrue(standardPublication > retention);
        assertTrue(immediateInitialization > standardPublication);
        assertFalse(method.contains("scopedState.ensureStructuresGenerated();"));
        assertFalse(method.contains("if (studioBootstrap && platformGenerator.isJigsawStudioActive())"));
    }

    @Test
    public void standardCompletionActivatesTheAlreadyPublishedStateWithoutReplacingIt() throws IOException {
        Path chunkGeneratorSource = Path.of(System.getProperty("iris.nmsChunkGeneratorSource"));
        String source = Files.readString(chunkGeneratorSource.resolveSibling("NMSBinding.java"));
        int methodStart = source.indexOf("public void completeStudioStructureBootstrap(World world)");
        int methodEnd = source.indexOf("\n    @Override\n    public void abandonStudioStructureBootstrap", methodStart);

        assertTrue(methodStart >= 0);
        assertTrue(methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);
        int retained = method.indexOf("generator.retainedStudioStructureState(level, chunkMap)");
        int activation = method.indexOf("generator.activateStudioStructureState(retained);");

        assertTrue(retained >= 0);
        assertTrue(activation > retained);
        assertFalse(method.contains("stateField.set("));
        assertFalse(method.contains("retained.fullState()"));
    }

    private static DatapackStructureScopeIndex index(
            List<String> structureKeys,
            List<String> structureSetKeys
    ) {
        return DatapackStructureScopeIndex.create(List.of(
                new DatapackIngestService.StructureScopeResources(
                        SOURCE,
                        structureKeys,
                        structureSetKeys)));
    }

    private static Holder<Structure> structureHolder(String key) {
        return new KeyedHolder<>(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(key)), null);
    }

    private static Holder<StructureSet> structureSetHolder(
            String key,
            Holder<Structure>... structures
    ) {
        return structureSetHolder(
                key,
                new RandomSpreadStructurePlacement(32, 8, RandomSpreadType.LINEAR, 12345),
                structures);
    }

    private static Holder<StructureSet> structureSetHolder(
            String key,
            StructurePlacement placement,
            Holder<Structure>... structures
    ) {
        List<StructureSet.StructureSelectionEntry> entries = Stream.of(structures)
                .map(structure -> new StructureSet.StructureSelectionEntry(structure, 1))
                .toList();
        StructureSet value = new StructureSet(entries, placement);
        return new KeyedHolder<>(
                ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse(key)),
                value);
    }

    private static final class KeyedHolder<T> implements Holder<T> {
        private final ResourceKey<T> key;
        private final T value;

        private KeyedHolder(ResourceKey<T> key, T value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public T value() {
            return value;
        }

        @Override
        public boolean isBound() {
            return true;
        }

        @Override
        public boolean areComponentsBound() {
            return true;
        }

        @Override
        public boolean is(Identifier identifier) {
            return key.identifier().equals(identifier);
        }

        @Override
        public boolean is(ResourceKey<T> candidate) {
            return key.equals(candidate);
        }

        @Override
        public boolean is(Predicate<ResourceKey<T>> predicate) {
            return predicate.test(key);
        }

        @Override
        public boolean is(TagKey<T> tag) {
            return false;
        }

        @Override
        public boolean is(Holder<T> holder) {
            return holder == this;
        }

        @Override
        public Stream<TagKey<T>> tags() {
            return Stream.empty();
        }

        @Override
        public DataComponentMap components() {
            return DataComponentMap.EMPTY;
        }

        @Override
        public Either<ResourceKey<T>, T> unwrap() {
            return Either.left(key);
        }

        @Override
        public Optional<ResourceKey<T>> unwrapKey() {
            return Optional.of(key);
        }

        @Override
        public Kind kind() {
            return Kind.REFERENCE;
        }

        @Override
        public boolean canSerializeIn(HolderOwner<T> owner) {
            return true;
        }
    }
}
