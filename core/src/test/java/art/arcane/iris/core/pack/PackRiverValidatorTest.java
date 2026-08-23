package art.arcane.iris.core.pack;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PackRiverValidatorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsEnabledNetworkWithFallbackBiomePools() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "logicalHeight": 256,
                  "rivers": {
                    "enabled": true,
                    "biomes": {
                      "channel": [],
                      "bank": [],
                      "mouth": [],
                      "dry": [],
                      "floodedCave": []
                    }
                  }
                }
                """);

        PackValidationResult result = PackValidator.validate(pack);

        assertTrue(result.getBlockingErrors().toString(), result.isLoadable());
    }

    @Test
    public void rejectsNullEnabledNetworkSections() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "topology": null,
                    "terrain": null,
                    "water": null,
                    "biomes": null,
                    "caves": null
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "rivers.topology must be an object.");
        assertContains(result.errors(), "rivers.terrain must be an object.");
        assertContains(result.errors(), "rivers.water must be an object.");
        assertContains(result.errors(), "rivers.biomes must be an object.");
        assertContains(result.errors(), "rivers.caves must be an object.");
    }

    @Test
    public void rejectsInvalidFiniteRangesAndMalformedStyles() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "topology": {
                      "siteJitter": 1e400,
                      "tileCells": 1,
                      "minimumSourcesPerTile": 2,
                      "sinkSearchReaches": 8,
                      "routingBasinCells": 7,
                      "routingPlateauHeight": 0,
                      "routingStyle": {"zoom": 0}
                    },
                    "terrain": {
                      "channelWidth": {"min": 40, "max": 12},
                      "depth": {}
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "rivers.topology.siteJitter must be a number.");
        assertContains(result.errors(), "rivers.topology.minimumSourcesPerTile must not exceed tileCells squared.");
        assertContains(result.errors(), "rivers.topology.sinkSearchReaches must be at most 7");
        assertContains(result.errors(), "rivers.topology.routingBasinCells must be at least 8");
        assertContains(result.errors(), "rivers.topology.routingPlateauHeight must be at least 1");
        assertContains(result.errors(), "rivers.topology.routingStyle.zoom must be at least");
        assertContains(result.errors(), "rivers.terrain.channelWidth.min must not exceed");
        assertContains(result.errors(), "rivers.terrain.depth must set min and max explicitly.");
    }

    @Test
    public void rejectsTerraceDropsAbovePermittedRise() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "water": {
                      "mode": "TERRACED",
                      "maximumPoolRise": 2,
                      "dropHeight": 3
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "rivers.water.dropHeight must not exceed maximumPoolRise");
    }

    @Test
    public void rejectsPathologicalCombinedTopologyComplexity() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "topology": {
                      "cellSize": 64,
                      "tileCells": 64,
                      "siteJitter": 0.49,
                      "maxRouteReaches": 256
                    },
                    "terrain": {
                      "channelWidth": {"min": 2048, "max": 2048},
                      "bankWidth": {"min": 2048, "max": 2048},
                      "orderWidthFactor": 8,
                      "meanderStrength": 1024,
                      "meanderSubdivisions": 64
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "exceeds the safe derived complexity budget");
        assertContains(result.errors(), "source window requires");
        assertContains(result.errors(), "increase cellSize or reduce tileCells");
    }

    @Test
    public void rejectsRiverGeometryCapsOutsideSupportedRanges() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {
                      "maxChannelWidth": 0,
                      "maxBankWidth": -1,
                      "maxDepth": 513
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "rivers.terrain.maxChannelWidth");
        assertContains(result.errors(), "rivers.terrain.maxBankWidth");
        assertContains(result.errors(), "rivers.terrain.maxDepth");
    }

    @Test
    public void rejectsMissingBiomeReferencesAndWarnsAboutInferredRoles() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "biomes": {
                      "channel": ["biome", "missing"],
                      "bank": ["biome"]
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "references missing biome 'missing'");
        assertContains(result.warnings(), "inferred river role SEA");
        assertContains(result.warnings(), "inferred river role SHORE");
    }

    @Test
    public void validatesRegionAndBiomeOverridesWhenRiversAreEnabled() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {"enabled": true}
                }
                """);
        write(pack, "regions/region.json", """
                {
                  "landBiomes": ["biome"],
                  "riverOverride": {
                    "routingCostMultiplier": -1,
                    "channelBiomes": ["missing"]
                  }
                }
                """);
        write(pack, "biomes/biome.json", """
                {
                  "name": "Biome",
                  "riverOverride": {
                    "allowSources": "yes",
                    "bankBiomes": []
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "Region 'region' riverOverride.routingCostMultiplier must be at least");
        assertContains(result.errors(), "Region 'region' riverOverride.channelBiomes[0] references missing biome");
        assertContains(result.errors(), "Biome 'biome' riverOverride.allowSources must be a boolean");
    }

    @Test
    public void rejectsZeroChannelAndDepthMultipliersInsteadOfRestoringFallbackGeometry() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {"enabled": true}
                }
                """);
        write(pack, "regions/region.json", """
                {
                  "landBiomes": ["biome"],
                  "riverOverride": {
                    "widthMultiplier": 0,
                    "depthMultiplier": 0
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "riverOverride.widthMultiplier must be at least 1.0E-4");
        assertContains(result.errors(), "riverOverride.depthMultiplier must be at least 1.0E-4");
    }

    @Test
    public void rejectsMathematicallyImpossibleGeneratedGrotto() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "caves": {
                      "mode": "GENERATE_GROTTO",
                      "throatRadius": 4,
                      "grottoHorizontalRadius": 4,
                      "grottoVerticalRadius": 4,
                      "grottoWarpStrength": 3,
                      "dryHeadroom": 9,
                      "maxFloodRadius": 6,
                      "maxFloodDepth": 6,
                      "maxFloodVolume": 64
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "throatRadius must be smaller than both grotto radii");
        assertContains(result.errors(), "dryHeadroom must fit inside the generated grotto height");
        assertContains(result.errors(), "maxFloodRadius must be at least 8");
        assertContains(result.errors(), "maxFloodDepth must be at least 8");
        assertContains(result.errors(), "maxFloodVolume must be at least");
    }

    @Test
    public void warnsWhenActiveCaveEntryGateCannotProduceConnections() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "caves": {
                      "mode": "FLOOD_CLOSED_COMPONENT",
                      "maximumPerReach": 0,
                      "maxBoreDepth": 64,
                      "maxFloodDepth": 32
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.warnings(), "entry gate cannot accept any connections");
        assertContains(result.warnings(), "maxBoreDepth exceeds maxFloodDepth");
    }

    @Test
    public void rejectsSinkholeTerminalWithoutCaveHydrology() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"terminalMode": "SINKHOLE_GROTTO"},
                    "caves": {"mode": "SEALED"}
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "SINKHOLE_GROTTO requires a non-SEALED caves.mode");
    }

    @Test
    public void rejectsSinkholeTerminalWhenPerReachCapDisablesItsForcedAnchor() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"terminalMode": "SINKHOLE_GROTTO"},
                    "caves": {
                      "mode": "GENERATE_GROTTO",
                      "maximumPerReach": 0
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "SINKHOLE_GROTTO requires caves.maximumPerReach above zero");
    }

    @Test
    public void rejectsSinkholeTerminalWhenCarvingIsDisabled() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "carvingEnabled": false,
                  "rivers": {
                    "enabled": true,
                    "terrain": {"terminalMode": "SINKHOLE_GROTTO"},
                    "caves": {"mode": "GENERATE_GROTTO"}
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "SINKHOLE_GROTTO requires carvingEnabled to be true");
    }

    @Test
    public void forcedSinkholeValidatesGrottoEnvelopeInClosedComponentModeOnce() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"terminalMode": "SINKHOLE_GROTTO"},
                    "caves": {
                      "mode": "FLOOD_CLOSED_COMPONENT",
                      "fallback": "SEALED",
                      "grottoHorizontalRadius": 12,
                      "grottoWarpStrength": 2,
                      "maxFloodRadius": 12
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        String fragment = "rivers.caves.maxFloodRadius must be at least 15";
        assertContains(result.errors(), fragment);
        assertTrue(result.errors().toString(), count(result.errors(), fragment) == 1);
    }

    @Test
    public void rejectsReachableRegionSinkholeAgainstInactiveDimensionHydrology() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "caves": {"mode": "SEALED"}
                  }
                }
                """);
        write(pack, "regions/region.json", """
                {
                  "landBiomes": ["biome"],
                  "riverOverride": {"terminalMode": "SINKHOLE_GROTTO"}
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "Region 'region' riverOverride.terminalMode SINKHOLE_GROTTO requires a non-SEALED caves.mode");
    }

    @Test
    public void rejectsReachableChildBiomeSinkholeAgainstDisabledCarving() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "carvingEnabled": false,
                  "rivers": {
                    "enabled": true,
                    "caves": {"mode": "GENERATE_GROTTO"}
                  }
                }
                """);
        write(pack, "biomes/biome.json", """
                {
                  "name": "Biome",
                  "derivative": "minecraft:plains",
                  "children": ["child"]
                }
                """);
        write(pack, "biomes/child.json", """
                {
                  "name": "Child",
                  "derivative": "minecraft:forest",
                  "riverOverride": {"terminalMode": "SINKHOLE_GROTTO"}
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "Biome 'child' riverOverride.terminalMode SINKHOLE_GROTTO requires carvingEnabled to be true");
    }

    @Test
    public void rejectsTransitiveFinalTerrainExpressionDependency() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "topology": {
                      "routingStyle": {"expression": "route"}
                    }
                  }
                }
                """);
        write(pack, "expressions/route.json", """
                {
                  "variables": [
                    {
                      "name": "nested",
                      "styleValue": {"expression": "terrain"}
                    }
                  ],
                  "expression": "nested"
                }
                """);
        write(pack, "expressions/terrain.json", """
                {
                  "functions": [
                    {
                      "name": "height",
                      "engineStreamValue": "HEIGHT"
                    }
                  ],
                  "expression": "height(x,z)"
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "engineStreamValue 'HEIGHT'");
        assertContains(result.errors(), "would recurse during river generation");
    }

    @Test
    public void rejectsFinalTerrainDependencyInsideExpressionEntrySnippet() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "topology": {
                      "routingStyle": {"expression": "route"}
                    }
                  }
                }
                """);
        write(pack, "expressions/route.json", """
                {
                  "variables": ["snippet/expression-load/final-height"],
                  "expression": "height"
                }
                """);
        write(pack, "snippet/expression-load/final-height.json", """
                {
                  "name": "height",
                  "engineStreamValue": "HEIGHT_OR_FLUID"
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "engineStreamValue 'HEIGHT_OR_FLUID'");
    }

    @Test
    public void rejectsCyclicStyleSnippetDependenciesWithoutRecursing() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "topology": {
                      "routingStyle": "snippet/style/first"
                    }
                  }
                }
                """);
        write(pack, "snippet/style/first.json", "{\"fracture\":\"snippet/style/second\"}");
        write(pack, "snippet/style/second.json", "{\"fracture\":\"snippet/style/first\"}");

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "cyclic river-noise style snippet dependency");
    }

    @Test
    public void acceptsNaturalHeightExpressionDependency() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "topology": {
                      "routingStyle": {"expression": "route"}
                    }
                  }
                }
                """);
        write(pack, "expressions/route.json", """
                {
                  "variables": [
                    {
                      "name": "height",
                      "engineStreamValue": "NATURAL_HEIGHT"
                    }
                  ],
                  "expression": "height"
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertFalse(result.errors().toString(), contains(result.errors(), "engineStreamValue"));
    }

    private PackRiverValidator.Validation validate(File pack) {
        File[] dimensions = new File(pack, "dimensions").listFiles(
                file -> file.isFile() && file.getName().endsWith(".json"));
        return PackRiverValidator.validate(pack, dimensions);
    }

    private File pack(String dimensionJson) throws Exception {
        File pack = temporaryFolder.newFolder("pack-" + System.nanoTime());
        write(pack, "dimensions/main.json", dimensionJson);
        write(pack, "regions/region.json", "{\"landBiomes\":[\"biome\"]}");
        write(pack, "biomes/biome.json",
                "{\"name\":\"Biome\",\"derivative\":\"minecraft:plains\"}");
        return pack;
    }

    private void assertContains(List<String> messages, String fragment) {
        assertTrue(messages.toString(), contains(messages, fragment));
    }

    private boolean contains(List<String> messages, String fragment) {
        for (String message : messages) {
            if (message.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private int count(List<String> messages, String fragment) {
        int matches = 0;
        for (String message : messages) {
            if (message.contains(fragment)) {
                matches++;
            }
        }
        return matches;
    }

    private void write(File root, String relative, String content) throws Exception {
        Path target = new File(root, relative).toPath();
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
