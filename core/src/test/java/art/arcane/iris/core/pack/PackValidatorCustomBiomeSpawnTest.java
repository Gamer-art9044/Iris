package art.arcane.iris.core.pack;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PackValidatorCustomBiomeSpawnTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsExplicitMatchingSpawnGroup() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"spawns\":[{\"type\":\"minecraft:slime\",\"group\":\"MONSTER\"}]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(
                biomes, key -> PackSpawnValidator.SpawnCategoryResolution.known("monster"));

        assertTrue(errors.isEmpty());
    }

    @Test
    public void acceptsBiomeWithoutCustomSpawns() throws Exception {
        File biomes = createBiomes("{\"name\":\"Plains\"}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(
                biomes, key -> PackSpawnValidator.SpawnCategoryResolution.known("monster"));

        assertTrue(errors.isEmpty());
    }

    @Test
    public void rejectsMissingSpawnGroup() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"spawns\":[{\"type\":\"minecraft:slime\"}]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(
                biomes, key -> PackSpawnValidator.SpawnCategoryResolution.known("monster"));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("must declare group 'MONSTER'"));
    }

    @Test
    public void acceptsImplicitMiscSpawnGroup() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"effects\",\"spawns\":[{\"type\":\"minecraft:armor_stand\"}]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(
                biomes, key -> PackSpawnValidator.SpawnCategoryResolution.known("misc"));

        assertTrue(errors.isEmpty());
    }

    @Test
    public void rejectsSpawnGroupThatDisagreesWithRegistry() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"spawns\":[{\"type\":\"minecraft:slime\",\"group\":\"MISC\"}]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(
                biomes, key -> PackSpawnValidator.SpawnCategoryResolution.known("monster"));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("live entity registry requires 'MONSTER'"));
    }

    @Test
    public void acceptsAxolotlSpawnCategory() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"cave\",\"spawns\":[{\"type\":\"minecraft:axolotl\",\"group\":\"AXOLOTLS\"}]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(
                biomes, key -> PackSpawnValidator.SpawnCategoryResolution.known("axolotls"));

        assertTrue(errors.isEmpty());
    }

    @Test
    public void rejectsUnknownSpawnEntity() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"spawns\":[{\"type\":\"missing:entity\",\"group\":\"MISC\"}]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(
                biomes, key -> PackSpawnValidator.SpawnCategoryResolution.unknown());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("unknown entity type 'missing:entity'"));
    }

    @Test
    public void rejectsCaseNormalizedGroupThatRuntimeWouldNotParse() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"spawns\":[{\"type\":\"minecraft:slime\",\"group\":\"monster\"}]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(
                biomes, key -> PackSpawnValidator.SpawnCategoryResolution.known("monster"));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("unknown group 'monster'"));
    }

    @Test
    public void rejectsWrongCustomDerivativeContainerType() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":{}}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(biomes, null);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("customDerivitives must be an array"));
    }

    @Test
    public void rejectsWrongSpawnContainerType() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"spawns\":{}}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(biomes, null);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("spawns must be an array"));
    }

    @Test
    public void acceptsNullCustomDerivativeContainerAsAbsent() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":null}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(biomes, null);

        assertTrue(errors.isEmpty());
    }

    @Test
    public void acceptsNullSpawnContainerAsAbsent() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"spawns\":null}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(biomes, null);

        assertTrue(errors.isEmpty());
    }

    @Test
    public void acceptsNamespacedCustomBiomeTags() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"tags\":[\"minecraft:allows_surface_slime_spawns\"]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(biomes, null);

        assertTrue(errors.isEmpty());
    }

    @Test
    public void rejectsUnsafeCustomBiomeTags() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"tags\":[\"minecraft:../outside\"]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(biomes, null);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("invalid tag"));
    }

    @Test
    public void convertsSpawnCategoryResolverFailureIntoBlockingError() throws Exception {
        File biomes = createBiomes("{\"customDerivitives\":[{\"id\":\"swamp\",\"spawns\":[{\"type\":\"minecraft:slime\",\"group\":\"MONSTER\"}]}]}");

        List<String> errors = PackSpawnValidator.validateCustomBiomeSpawns(biomes, key -> {
            throw new IllegalStateException("registry unavailable");
        });

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("spawn category lookup failed"));
        assertTrue(errors.get(0).contains("registry unavailable"));
    }

    private File createBiomes(String json) throws Exception {
        File biomes = temporaryFolder.newFolder("biomes");
        File biome = new File(biomes, "test.json");
        Files.writeString(biome.toPath(), json, StandardCharsets.UTF_8);
        return biomes;
    }
}
