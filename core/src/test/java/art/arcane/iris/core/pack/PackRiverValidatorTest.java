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
                    "terrain": {"worms": [{"id": "river"}]},
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
    public void acceptsRequiredValidWormProfiles() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {
                      "worms": [
                        {
                          "id": "floodplain_trunk",
                          "seed": 17,
                          "weight": 2.5,
                          "wavelength": 1536,
                          "detailWavelength": 192,
                          "tortuosity": 0.65,
                          "detailTortuosity": 0.2,
                          "maxOffset": 420,
                          "segments": 56,
                          "widthMultiplier": 1.4,
                          "bankMultiplier": 1.2,
                          "depthMultiplier": 0.8,
                          "bodyWavelength": 1400,
                          "bodyDetailWavelength": 320,
                          "widthVariation": 0.65,
                          "bankVariation": 0.75,
                          "depthVariation": 0.45,
                          "roofVariation": 0.55,
                          "branchCap": 3,
                          "branchDecay": 0.25,
                          "confluenceMultiplier": 1.5,
                          "childChance": 0.2,
                          "branchChildChance": 0.6,
                          "children": [
                            {
                              "id": "floodplain_tributary",
                              "seed": 29,
                              "weight": 1,
                              "wavelength": 640,
                              "detailWavelength": 128,
                              "tortuosity": 0.8,
                              "detailTortuosity": 0.3,
                              "maxOffset": 300,
                              "segments": 48,
                              "widthMultiplier": 0.7,
                              "bankMultiplier": 0.9,
                              "depthMultiplier": 1.3,
                              "bodyWavelength": 180,
                              "bodyDetailWavelength": 48,
                              "widthVariation": 0.8,
                              "bankVariation": 0.8,
                              "depthVariation": 0.65,
                              "roofVariation": 0.75,
                              "branchCap": 2,
                              "branchDecay": 0.1,
                              "confluenceMultiplier": 0.75,
                              "childChance": 0,
                              "branchChildChance": 0,
                              "children": []
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsInvalidWormHierarchyIdentifiersRangesAndChildren() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {
                      "worms": [
                        {
                          "id": "trunk",
                          "seed": 17,
                          "bodyWavelength": 7,
                          "bodyDetailWavelength": 16385,
                          "bodyDetailInfluence": 1.1,
                          "widthVariation": -0.1,
                          "bankVariation": 0.876,
                          "depthVariation": -0.1,
                          "roofVariation": 0.876,
                          "branchCap": 0,
                          "branchDecay": 2,
                          "confluenceMultiplier": 9,
                          "childChance": -0.1,
                          "branchChildChance": 1.1,
                          "children": "tributary"
                        },
                        {"id": "trunk", "seed": 29},
                        {"id": "Bad ID", "seed": 31}
                      ]
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "rivers.terrain.worms[0].bodyWavelength must be at least 8");
        assertContains(result.errors(), "rivers.terrain.worms[0].bodyDetailWavelength must be at most 16384");
        assertContains(result.errors(), "rivers.terrain.worms[0].bodyDetailInfluence must be at most 1");
        assertContains(result.errors(), "rivers.terrain.worms[0].widthVariation must be at least 0");
        assertContains(result.errors(), "rivers.terrain.worms[0].bankVariation must be at most 0.875");
        assertContains(result.errors(), "rivers.terrain.worms[0].depthVariation must be at least 0");
        assertContains(result.errors(), "rivers.terrain.worms[0].roofVariation must be at most 0.875");
        assertContains(result.errors(), "rivers.terrain.worms[0].branchCap must be at least 1");
        assertContains(result.errors(), "rivers.terrain.worms[0].branchDecay must be at most 1");
        assertContains(result.errors(), "rivers.terrain.worms[0].confluenceMultiplier must be at most 8");
        assertContains(result.errors(), "rivers.terrain.worms[0].childChance must be at least 0");
        assertContains(result.errors(), "rivers.terrain.worms[0].branchChildChance must be at most 1");
        assertContains(result.errors(), "rivers.terrain.worms[0].children must be an array");
        assertContains(result.errors(), "rivers.terrain.worms[1].id must be unique inside the worm hierarchy");
        assertContains(result.errors(),
                "rivers.terrain.worms[2].id must use 1 to 64 lowercase letters, digits, underscores, or hyphens");
    }

    @Test
    public void rejectsWormHierarchyDepthAndProfileLimits() throws Exception {
        File excessiveDepth = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {
                      "worms": [
                        {
                          "id": "level_1",
                          "seed": 1,
                          "children": [
                            {
                              "id": "level_2",
                              "seed": 2,
                              "children": [
                                {
                                  "id": "level_3",
                                  "seed": 3,
                                  "children": [
                                    {
                                      "id": "level_4",
                                      "seed": 4,
                                      "children": [
                                        {"id": "level_5", "seed": 5}
                                      ]
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """);
        File excessiveRoots = packWithWorms(wormHierarchy(17, 0));
        File excessiveProfiles = packWithWorms(wormHierarchy(16, 8));

        PackRiverValidator.Validation depthResult = validate(excessiveDepth);
        PackRiverValidator.Validation rootResult = validate(excessiveRoots);
        PackRiverValidator.Validation profileResult = validate(excessiveProfiles);

        assertContains(depthResult.errors(), "children exceeds the maximum hierarchy depth of 4");
        assertContains(rootResult.errors(), "rivers.terrain.worms must contain at most 16 root profiles");
        assertContains(profileResult.errors(), "rivers.terrain.worms hierarchy must contain at most 128 profiles");
    }

    @Test
    public void rejectsMissingAndEmptyWormLists() throws Exception {
        File missing = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {}
                  }
                }
                """);
        File empty = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": []}
                  }
                }
                """);

        PackRiverValidator.Validation missingResult = validate(missing);
        PackRiverValidator.Validation emptyResult = validate(empty);

        assertContains(missingResult.errors(),
                "rivers.terrain.worms must be an array with at least one Perlin-worm profile");
        assertContains(emptyResult.errors(),
                "rivers.terrain.worms must contain at least one Perlin-worm profile");
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
                      "routingDeviationScaleCells": 7,
                      "routingDeviationStrengthCells": 33,
                      "routingPlateauHeight": 0,
                      "flowAlignmentWeight": 1025,
                      "confluenceWeight": -1,
                      "routingStyle": {"zoom": 0}
                    },
                    "terrain": {
                      "worms": [{"id": "river"}],
                      "channelWidth": {"min": 40, "max": 12},
                      "depth": {},
                      "tunnelWidthMultiplier": {"min": 0.5, "max": 9},
                      "tunnelMouthBlend": 17,
                      "tunnelFloorVariation": 9,
                      "tunnelRoofVariation": 17,
                      "tunnelFloorStyle": {"zoom": 0},
                      "tunnelRoofStyle": {"zoom": 0}
                    },
                    "caves": {
                      "parentBiomeInheritance": 2
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "rivers.topology.siteJitter must be a number.");
        assertContains(result.errors(), "rivers.topology.minimumSourcesPerTile must not exceed tileCells squared.");
        assertContains(result.errors(), "rivers.topology.sinkSearchReaches must be at most 7");
        assertContains(result.errors(), "rivers.topology.routingBasinCells must be at least 8");
        assertContains(result.errors(), "rivers.topology.routingDeviationScaleCells must be at least 8");
        assertContains(result.errors(), "rivers.topology.routingDeviationStrengthCells must be at most 32");
        assertContains(result.errors(), "rivers.topology.routingPlateauHeight must be at least 1");
        assertContains(result.errors(), "rivers.topology.flowAlignmentWeight must be at most 1024");
        assertContains(result.errors(), "rivers.topology.confluenceWeight must be at least 0");
        assertContains(result.errors(), "rivers.topology.routingStyle.zoom must be at least");
        assertContains(result.errors(), "rivers.terrain.channelWidth.min must not exceed");
        assertContains(result.errors(), "rivers.terrain.depth must set min and max explicitly.");
        assertContains(result.errors(), "rivers.terrain.tunnelWidthMultiplier.min must be at least 1");
        assertContains(result.errors(), "rivers.terrain.tunnelWidthMultiplier.max must be at most 8");
        assertContains(result.errors(), "rivers.terrain.tunnelMouthBlend must be at most 16");
        assertContains(result.errors(), "rivers.terrain.tunnelFloorVariation must be at most 8");
        assertContains(result.errors(), "rivers.terrain.tunnelRoofVariation must be at most 16");
        assertContains(result.errors(), "rivers.terrain.tunnelFloorStyle.zoom must be at least");
        assertContains(result.errors(), "rivers.terrain.tunnelRoofStyle.zoom must be at least");
        assertContains(result.errors(), "rivers.caves.parentBiomeInheritance must be at most 1");
    }

    @Test
    public void rejectsTerraceDropsAbovePermittedRise() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]},
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
    public void acceptsIndependentCaveLavaRiverHeightAndPalette() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -64, "max": 320},
                  "fluidHeight": 63,
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]},
                    "water": {
                      "mode": "FIXED",
                      "fluidHeight": -48,
                      "fluidPalette": {
                        "palette": [{"block": "minecraft:lava"}]
                      }
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void acceptsSparseBlobbyDeepLavaPoolsWithIndependentHeight() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -256, "max": 512},
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]},
                    "caves": {
                      "deepPools": {
                        "enabled": true,
                        "reach": {
                          "chance": 0.08,
                          "influence": 0.04,
                          "style": {"style": "IRIS", "zoom": 4096}
                        },
                        "minimumSpacing": 768,
                        "maximumPerReach": 1,
                        "minimumFluidY": -224,
                        "maximumFluidY": -108,
                        "searchRadius": 20,
                        "searchAttempts": 12,
                        "horizontalRadius": 24,
                        "verticalRadius": 10,
                        "dryHeadroom": 5,
                        "shapeStyle": {"style": "IRIS", "zoom": 12},
                        "shapeVariation": 0.6,
                        "warpStyle": {"style": "IRIS", "zoom": 24},
                        "warpStrength": 8,
                        "maximumVolume": 65536,
                        "fluidPalette": {
                          "palette": [{"block": "minecraft:lava"}]
                        }
                      }
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsUnsafeDeepPoolEnvelopeShapeAndPalette() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -64, "max": 320},
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]},
                    "caves": {
                      "deepPools": {
                        "enabled": true,
                        "reach": {"chance": 0},
                        "minimumFluidY": -90,
                        "maximumFluidY": -120,
                        "searchRadius": 120,
                        "horizontalRadius": 20,
                        "verticalRadius": 8,
                        "dryHeadroom": 8,
                        "maximumVolume": 64,
                        "fluidPalette": {"palette": []}
                      }
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "deepPools.minimumFluidY must not exceed maximumFluidY");
        assertContains(result.errors(), "deepPools.dryHeadroom must be smaller than verticalRadius");
        assertContains(result.errors(), "deepPools.searchRadius plus horizontalRadius must not exceed 128");
        assertContains(result.errors(), "deepPools.maximumVolume must be at least");
        assertContains(result.errors(), "deepPools.fluidPalette.palette must contain at least one fluid block");
        assertContains(result.errors(), "deepPools fluid range and chamber envelope must remain inside dimensionHeight");
        assertContains(result.warnings(), "deepPools is enabled but its reach gate cannot accept any pools");
    }

    @Test
    public void rejectsRetiredWaterModeInvalidPaletteAndOutOfBoundsHeight() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -64, "max": 320},
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]},
                    "water": {
                      "mode": "SEA_LEVEL",
                      "fluidHeight": -80,
                      "fluidPalette": {"palette": []}
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "rivers.water.mode must be one of");
        assertContains(result.errors(), "rivers.water.fluidHeight must remain inside dimensionHeight");
        assertContains(result.errors(), "rivers.water.fluidPalette.palette must contain at least one fluid block");
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
                      "worms": [{"id": "river", "maxOffset": 1024, "segments": 64}]
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
                      "worms": [{"id": "river"}],
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
    public void rejectsTunnelFootprintsAboveDerivedHydrologyBudget() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {
                      "worms": [{"id": "river"}],
                      "maxChannelWidth": 2048,
                      "tunnelMouthBlend": 16
                    }
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertContains(result.errors(), "exceeds the safe derived hydrology budget");
        assertContains(result.errors(), "columns per generated chunk");
    }

    @Test
    public void rejectsMissingBiomeReferencesAndWarnsAboutInferredRoles() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]},
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
    public void netherRiverBiomesKeepNetherDerivativesWithoutOverworldRoleWarnings() throws Exception {
        File pack = pack("""
                {
                  "environment": "NETHER",
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]},
                    "biomes": {
                      "channel": ["biome"],
                      "bank": ["biome"]
                    }
                  }
                }
                """);
        write(pack, "biomes/biome.json", """
                {
                  "name": "Nether River",
                  "derivative": "minecraft:basalt_deltas",
                  "vanillaDerivative": "minecraft:basalt_deltas"
                }
                """);
        write(pack, "regions/region.json", """
                {
                  "landBiomes": ["biome"],
                  "riverOverride": {
                    "channelBiomes": ["biome"],
                    "bankBiomes": ["biome"]
                  }
                }
                """);

        PackRiverValidator.Validation result = validate(pack);

        assertFalse(result.errors().toString(), contains(result.errors(), "biome"));
        assertFalse(result.warnings().toString(), contains(result.warnings(), "inferred river role"));
    }

    @Test
    public void validatesRegionAndBiomeOverridesWhenRiversAreEnabled() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]}
                  }
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
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": [{"id": "river"}]}
                  }
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
                    "terrain": {"worms": [{"id": "river"}]},
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
                    "terrain": {"worms": [{"id": "river"}]},
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
                    "terrain": {
                      "worms": [{"id": "river"}],
                      "terminalMode": "SINKHOLE_GROTTO"
                    },
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
                    "terrain": {
                      "worms": [{"id": "river"}],
                      "terminalMode": "SINKHOLE_GROTTO"
                    },
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
                    "terrain": {
                      "worms": [{"id": "river"}],
                      "terminalMode": "SINKHOLE_GROTTO"
                    },
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
                    "terrain": {
                      "worms": [{"id": "river"}],
                      "terminalMode": "SINKHOLE_GROTTO"
                    },
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
                    "terrain": {"worms": [{"id": "river"}]},
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
                    "terrain": {"worms": [{"id": "river"}]},
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
                    "terrain": {"worms": [{"id": "river"}]},
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
                    "terrain": {"worms": [{"id": "river"}]},
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
                    "terrain": {"worms": [{"id": "river"}]},
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
                    "terrain": {"worms": [{"id": "river"}]},
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

    private File packWithWorms(String worms) throws Exception {
        return pack("""
                {
                  "regions": ["region"],
                  "rivers": {
                    "enabled": true,
                    "terrain": {"worms": %s}
                  }
                }
                """.formatted(worms));
    }

    private String wormHierarchy(int rootCount, int childrenPerRoot) {
        StringBuilder hierarchy = new StringBuilder("[");
        int seed = 1;
        for (int rootIndex = 0; rootIndex < rootCount; rootIndex++) {
            if (rootIndex > 0) {
                hierarchy.append(',');
            }
            hierarchy.append("{\"id\":\"root_")
                    .append(rootIndex)
                    .append("\",\"seed\":")
                    .append(seed++);
            if (childrenPerRoot > 0) {
                hierarchy.append(",\"children\":[");
                for (int childIndex = 0; childIndex < childrenPerRoot; childIndex++) {
                    if (childIndex > 0) {
                        hierarchy.append(',');
                    }
                    hierarchy.append("{\"id\":\"child_")
                            .append(rootIndex)
                            .append('_')
                            .append(childIndex)
                            .append("\",\"seed\":")
                            .append(seed++)
                            .append('}');
                }
                hierarchy.append(']');
            }
            hierarchy.append('}');
        }
        return hierarchy.append(']').toString();
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
