package art.arcane.iris.core.pack;

import art.arcane.iris.engine.object.NoiseStyle;
import art.arcane.iris.engine.river.RiverTopologyComplexity;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PackRiverValidator {
    private static final Set<String> WATER_MODES = Set.of("SEA_LEVEL", "TERRACED");
    private static final Set<String> TERMINAL_MODES = Set.of("SUPPRESS", "DRY_CHANNEL", "SINKHOLE_GROTTO");
    private static final Set<String> ROUTING_POLICIES = Set.of("ALLOW", "AVOID", "BLOCK");
    private static final Set<String> CAVE_MODES = Set.of(
            "SEALED",
            "FLOOD_CLOSED_COMPONENT",
            "GENERATE_GROTTO",
            "GROTTO_OR_CLOSED_COMPONENT",
            "WATERFALL_POOL"
    );
    private static final Set<String> CAVE_FALLBACKS = Set.of("SEALED", "GENERATE_GROTTO");
    private static final Set<String> EXISTING_FLUID_POLICIES = Set.of("REJECT", "ALLOW_SAME", "REPLACE");
    private static final Set<String> UNSAFE_RIVER_STREAMS = Set.of("HEIGHT", "HEIGHT_OR_FLUID", "SLOPE");
    private static final Set<String> NOISE_STYLES = noiseStyles();

    private PackRiverValidator() {
    }

    static Validation validate(File packFolder, File[] dimensionFiles) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (packFolder == null || !packFolder.isDirectory() || dimensionFiles == null) {
            return new Validation(errors, warnings);
        }

        boolean enabled = false;
        List<DimensionRiverContext> contexts = new ArrayList<>();
        List<File> sortedDimensions = new ArrayList<>(List.of(dimensionFiles));
        sortedDimensions.sort(Comparator.comparing(File::getPath));
        for (File dimensionFile : sortedDimensions) {
            JSONObject dimension = PackValidationIo.readJson(dimensionFile);
            if (dimension == null || !dimension.has("rivers")) {
                continue;
            }
            String dimensionKey = PackValidationIo.stripExtension(dimensionFile.getName());
            String path = "Dimension '" + dimensionKey + "' rivers";
            JSONObject rivers = requireObject(dimension, "rivers", path, errors);
            if (rivers == null) {
                continue;
            }
            PackJsonFieldChecks.validateOptionalBoolean(path, rivers, "enabled", errors);
            if (!booleanValue(rivers, "enabled", false)) {
                continue;
            }
            enabled = true;
            DimensionRiverContext context = new DimensionRiverContext(
                    dimensionKey,
                    dimension,
                    rivers,
                    referencedKeys(dimension.optJSONArray("regions"))
            );
            contexts.add(context);
            validateNetwork(packFolder, path, context, errors, warnings);
        }

        if (enabled) {
            validateOverrides(packFolder, new File(packFolder, "regions"), "Region", contexts, errors, warnings);
            validateOverrides(packFolder, new File(packFolder, "biomes"), "Biome", contexts, errors, warnings);
        }
        return new Validation(errors, warnings);
    }

    private static void validateNetwork(File packFolder, String path, DimensionRiverContext context,
                                        List<String> errors, List<String> warnings) {
        JSONObject rivers = context.rivers();
        JSONObject topology = nestedObject(rivers, "topology", path, errors);
        JSONObject terrain = nestedObject(rivers, "terrain", path, errors);
        JSONObject water = nestedObject(rivers, "water", path, errors);
        JSONObject biomes = nestedObject(rivers, "biomes", path, errors);
        JSONObject caves = nestedObject(rivers, "caves", path, errors);

        if (topology != null) {
            validateTopology(packFolder, path + ".topology", topology, errors, warnings);
        }
        if (terrain != null) {
            validateTerrain(packFolder, path + ".terrain", terrain, errors, warnings);
        }
        if (water != null) {
            validateWater(path + ".water", water, errors);
        }
        if (biomes != null) {
            validateBiomePools(packFolder, path + ".biomes", biomes, false, errors, warnings);
        }
        boolean sinkholeTerminal = terrain != null
                && "SINKHOLE_GROTTO".equals(stringValue(terrain, "terminalMode", "DRY_CHANNEL"));
        if (caves != null) {
            validateCaves(packFolder, path + ".caves", caves, sinkholeTerminal, errors, warnings);
        }

        if (topology != null && terrain != null) {
            double meanderStrength = doubleValue(terrain, "meanderStrength", 72D);
            int cellSize = integerValue(topology, "cellSize", 512);
            if (Double.isFinite(meanderStrength) && meanderStrength > cellSize) {
                warnings.add(path + ".terrain.meanderStrength exceeds topology.cellSize; reaches may require large cache halos.");
            }
            validateTopologyComplexity(path, topology, terrain, errors);
        }
        if (sinkholeTerminal && caves != null) {
            validateSinkholeCapability(
                    path + ".terrain.terminalMode",
                    context,
                    caves,
                    path + ".caves",
                    errors
            );
        }
    }

    private static void validateTopology(File packFolder, String path, JSONObject topology,
                                         List<String> errors, List<String> warnings) {
        PackJsonFieldChecks.validateOptionalIntegerRange(path, topology, "cellSize", 64, 4096, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, topology, "tileCells", 1, 64, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, topology, "siteJitter", 0D, 0.49D, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, topology, "maxRouteReaches", 1, 256, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, topology, "minimumSourcesPerTile", 0, 64, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, topology, "sinkSearchReaches", 0, 7, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, topology, "routingBasinCells", 8, 256, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, topology, "routingPlateauHeight", 1D, 64D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, topology, "routingNoiseWeight", 0D, 1024D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, topology, "terrainHeightWeight", 0D, 16D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, topology, "terrainSlopeWeight", 0D, 16D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, topology, "oceanAttraction", 0D, 16D, errors);
        PackJsonFieldChecks.validateOptionalBoolean(path, topology, "requireOcean", errors);
        validateNoiseChance(packFolder, topology, "source", path, errors);
        validateNoiseChance(packFolder, topology, "continuation", path, errors);
        validateStyle(packFolder, topology, "routingStyle", path, errors);

        int tileCells = integerValue(topology, "tileCells", 4);
        int minimumSourcesPerTile = integerValue(topology, "minimumSourcesPerTile", 0);
        if (tileCells >= 1 && tileCells <= 64
                && minimumSourcesPerTile >= 0
                && minimumSourcesPerTile > tileCells * tileCells) {
            errors.add(path + ".minimumSourcesPerTile must not exceed tileCells squared.");
        }

    }

    private static void validateTerrain(File packFolder, String path, JSONObject terrain,
                                        List<String> errors, List<String> warnings) {
        validateStyledRange(packFolder, terrain, "channelWidth", path, 1D, 2048D, errors, warnings);
        validateStyledRange(packFolder, terrain, "bankWidth", path, 0D, 2048D, errors, warnings);
        validateStyledRange(packFolder, terrain, "depth", path, 1D, 512D, errors, warnings);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "maxChannelWidth", 1D, 2048D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "maxBankWidth", 0D, 2048D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "maxDepth", 1D, 512D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "orderWidthFactor", 0D, 8D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "orderDepthFactor", 0D, 8D, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, terrain, "maxIncision", 0, 512, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "bankExponent", 0.125D, 16D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "meanderStrength", 0D, 1024D, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, terrain, "meanderSubdivisions", 1, 64, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "bedRoughness", 0D, 8D, errors);
        PackJsonFieldChecks.validateOptionalEnum(path, terrain, "terminalMode", TERMINAL_MODES, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, terrain, "terminalTaper", 8, 1024, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, terrain, "dryContinuationChance", 0D, 1D, errors);
        validateNoiseChance(packFolder, terrain, "incision", path, errors);
        validateStyle(packFolder, terrain, "meanderStyle", path, errors);
        validateStyle(packFolder, terrain, "bedRoughnessStyle", path, errors);
    }

    private static void validateWater(String path, JSONObject water, List<String> errors) {
        PackJsonFieldChecks.validateOptionalEnum(path, water, "mode", WATER_MODES, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, water, "poolLength", 8, 4096, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, water, "maximumPoolRise", 0, 64, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, water, "dropHeight", 1, 32, errors);

        String mode = stringValue(water, "mode", "SEA_LEVEL");
        int maximumPoolRise = integerValue(water, "maximumPoolRise", 4);
        int dropHeight = integerValue(water, "dropHeight", 1);
        if ("TERRACED".equals(mode) && dropHeight > maximumPoolRise) {
            errors.add(path + ".dropHeight must not exceed maximumPoolRise in TERRACED mode.");
        }
    }

    private static void validateTopologyComplexity(
            String path,
            JSONObject topology,
            JSONObject terrain,
            List<String> errors
    ) {
        int cellSize = integerValue(topology, "cellSize", 512);
        int tileCells = integerValue(topology, "tileCells", 4);
        double siteJitter = doubleValue(topology, "siteJitter", 0.35D);
        int maxRouteReaches = integerValue(topology, "maxRouteReaches", 16);
        double meanderStrength = doubleValue(terrain, "meanderStrength", 72D);
        int meanderSubdivisions = integerValue(terrain, "meanderSubdivisions", 8);
        double maximumChannelWidth = doubleValue(terrain, "maxChannelWidth", 10D);
        double maximumBankWidth = doubleValue(terrain, "maxBankWidth", 4D);
        if (cellSize < 64 || cellSize > 4096
                || tileCells < 1 || tileCells > 64
                || !Double.isFinite(siteJitter) || siteJitter < 0D || siteJitter > 0.49D
                || maxRouteReaches < 1 || maxRouteReaches > 256
                || !Double.isFinite(meanderStrength) || meanderStrength < 0D || meanderStrength > 1024D
                || meanderSubdivisions < 1 || meanderSubdivisions > 64
                || !Double.isFinite(maximumChannelWidth) || maximumChannelWidth < 1D || maximumChannelWidth > 2048D
                || !Double.isFinite(maximumBankWidth) || maximumBankWidth < 0D || maximumBankWidth > 2048D) {
            return;
        }
        double maximumReachRadius = maximumChannelWidth * 0.5D + maximumBankWidth;
        RiverTopologyComplexity.Estimate estimate = RiverTopologyComplexity.estimate(
                cellSize,
                tileCells,
                siteJitter,
                maxRouteReaches,
                maximumReachRadius,
                meanderStrength,
                meanderSubdivisions
        );
        for (String violation : estimate.violations()) {
            errors.add(path + " exceeds the safe derived complexity budget. " + violation);
        }
    }

    private static void validateCaves(File packFolder, String path, JSONObject caves,
                                      boolean forceGeneratedGrotto,
                                      List<String> errors, List<String> warnings) {
        PackJsonFieldChecks.validateOptionalEnum(path, caves, "mode", CAVE_MODES, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "minimumSpacing", 16, 4096, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "maximumPerReach", 0, 16, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "maxBoreDepth", 1, 256, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "throatRadius", 1, 16, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "waterLevelOffset", -64, 64, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "dryHeadroom", 0, 64, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "grottoHorizontalRadius", 2, 128, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "grottoVerticalRadius", 2, 128, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, caves, "grottoWarpStrength", 0D, 32D, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "maxFloodRadius", 4, 256, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "maxFloodDepth", 4, 256, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, caves, "maxFloodVolume", 64, 1048576, errors);
        PackJsonFieldChecks.validateOptionalEnum(path, caves, "fallback", CAVE_FALLBACKS, errors);
        PackJsonFieldChecks.validateOptionalEnum(path, caves, "existingFluidPolicy", EXISTING_FLUID_POLICIES, errors);
        validateNoiseChance(packFolder, caves, "entry", path, errors);
        validateStyle(packFolder, caves, "grottoShapeStyle", path, errors);
        validateStyle(packFolder, caves, "grottoWarpStyle", path, errors);

        String mode = stringValue(caves, "mode", "SEALED");
        if ("SEALED".equals(mode) && !forceGeneratedGrotto) {
            return;
        }

        int maximumPerReach = integerValue(caves, "maximumPerReach", 1);
        double entryChance = noiseChanceValue(caves, "entry", 0.12D);
        if (maximumPerReach == 0 || (!forceGeneratedGrotto && entryChance == 0D)) {
            warnings.add(path + " enables cave hydrology but its entry gate cannot accept any connections.");
        }

        int maxBoreDepth = integerValue(caves, "maxBoreDepth", 48);
        int throatRadius = integerValue(caves, "throatRadius", 2);
        int maxFloodRadius = integerValue(caves, "maxFloodRadius", 48);
        int maxFloodDepth = integerValue(caves, "maxFloodDepth", 32);
        if (throatRadius >= maxFloodRadius) {
            errors.add(path + ".throatRadius must be smaller than maxFloodRadius so the proof boundary can contain the throat.");
        }
        if (throatRadius >= maxFloodDepth) {
            errors.add(path + ".throatRadius must be smaller than maxFloodDepth so the proof boundary can contain the throat.");
        }
        if (maxBoreDepth > maxFloodDepth) {
            warnings.add(path + ".maxBoreDepth exceeds maxFloodDepth; deeper cave targets found by the bore search will be rejected by containment proof.");
        }

        String fallback = stringValue(caves, "fallback", "SEALED");
        if (forceGeneratedGrotto || usesGeneratedGrotto(mode, fallback)) {
            validateGrotto(path, caves, errors);
        }
    }

    private static void validateGrotto(String path, JSONObject caves, List<String> errors) {
        int throatRadius = integerValue(caves, "throatRadius", 2);
        int dryHeadroom = integerValue(caves, "dryHeadroom", 4);
        int horizontalRadius = integerValue(caves, "grottoHorizontalRadius", 12);
        int verticalRadius = integerValue(caves, "grottoVerticalRadius", 7);
        double warpStrength = doubleValue(caves, "grottoWarpStrength", 2D);
        int maxFloodRadius = integerValue(caves, "maxFloodRadius", 48);
        int maxFloodDepth = integerValue(caves, "maxFloodDepth", 32);
        int maxFloodVolume = integerValue(caves, "maxFloodVolume", 8192);
        if (throatRadius >= horizontalRadius || throatRadius >= verticalRadius) {
            addDistinct(errors, path + ".throatRadius must be smaller than both grotto radii so a sealed chamber can surround the inlet.");
        }
        if (dryHeadroom >= (verticalRadius * 2) + 1) {
            addDistinct(errors, path + ".dryHeadroom must fit inside the generated grotto height.");
        }

        int warpEnvelope = Double.isFinite(warpStrength) ? (int) Math.ceil(warpStrength) : 0;
        int requiredRadius = horizontalRadius + warpEnvelope + 1;
        int requiredDepth = verticalRadius + warpEnvelope + 1;
        if (maxFloodRadius < requiredRadius) {
            addDistinct(errors, path + ".maxFloodRadius must be at least " + requiredRadius
                    + " to prove the configured grotto and its sealed shell.");
        }
        if (maxFloodDepth < requiredDepth) {
            addDistinct(errors, path + ".maxFloodDepth must be at least " + requiredDepth
                    + " to prove the configured grotto and its sealed shell.");
        }

        long volume = grottoVolume(horizontalRadius, verticalRadius);
        if (volume > maxFloodVolume) {
            addDistinct(errors, path + ".maxFloodVolume must be at least " + volume
                    + " to contain the configured grotto before its throat and shell are considered.");
        }
    }

    private static long grottoVolume(int horizontalRadius, int verticalRadius) {
        long volume = 0L;
        double horizontalSquared = (double) horizontalRadius * horizontalRadius;
        double verticalSquared = (double) verticalRadius * verticalRadius;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                double remaining = 1D - ((double) dx * dx / horizontalSquared)
                        - ((double) dy * dy / verticalSquared);
                if (remaining < 0D) {
                    continue;
                }
                int maximumZ = (int) Math.floor(horizontalRadius * Math.sqrt(remaining));
                volume += (maximumZ * 2L) + 1L;
            }
        }
        return volume;
    }

    private static boolean usesGeneratedGrotto(String mode, String fallback) {
        return "GENERATE_GROTTO".equals(mode)
                || "GROTTO_OR_CLOSED_COMPONENT".equals(mode)
                || "WATERFALL_POOL".equals(mode)
                || "GENERATE_GROTTO".equals(fallback);
    }

    private static void validateSinkholeCapability(
            String terminalPath,
            DimensionRiverContext context,
            JSONObject caves,
            String cavesPath,
            List<String> errors
    ) {
        String suffix = " in Dimension '" + context.dimensionKey() + "'.";
        if (!booleanValue(context.dimension(), "carvingEnabled", true)) {
            addDistinct(errors, terminalPath + " SINKHOLE_GROTTO requires carvingEnabled to be true" + suffix);
        }
        if (!booleanValue(context.dimension(), "useMantle", true)) {
            addDistinct(errors, terminalPath + " SINKHOLE_GROTTO requires useMantle to be true" + suffix);
        }
        if (disabled(context.dimension(), "CARVED")) {
            addDistinct(errors, terminalPath + " SINKHOLE_GROTTO requires CARVED to remain enabled" + suffix);
        }
        if (disabled(context.dimension(), "RIVER_HYDROLOGY")) {
            addDistinct(errors, terminalPath + " SINKHOLE_GROTTO requires RIVER_HYDROLOGY to remain enabled" + suffix);
        }
        if ("SEALED".equals(stringValue(caves, "mode", "SEALED"))) {
            addDistinct(errors, terminalPath + " SINKHOLE_GROTTO requires a non-SEALED caves.mode" + suffix);
        }
        if (integerValue(caves, "maximumPerReach", 1) <= 0) {
            addDistinct(errors, terminalPath + " SINKHOLE_GROTTO requires caves.maximumPerReach above zero" + suffix);
        }
        validateGrotto(cavesPath, caves, errors);
    }

    private static void validateOverrideSinkhole(
            File packFolder,
            String terminalPath,
            String resourceKey,
            String resourceType,
            List<DimensionRiverContext> contexts,
            List<String> errors,
            List<String> warnings
    ) {
        boolean referenced = false;
        for (DimensionRiverContext context : contexts) {
            boolean reachable = "Region".equals(resourceType)
                    ? context.regionKeys().contains(resourceKey)
                    : referencedSurfaceBiomes(packFolder, context.regionKeys()).contains(resourceKey);
            if (!reachable) {
                continue;
            }
            referenced = true;
            JSONObject caves = nestedObject(context.rivers(), "caves", "Dimension '"
                    + context.dimensionKey() + "' rivers", errors);
            if (caves != null) {
                validateSinkholeCapability(
                        terminalPath,
                        context,
                        caves,
                        "Dimension '" + context.dimensionKey() + "' rivers.caves",
                        errors
                );
            }
        }
        if (!referenced) {
            addDistinct(warnings, terminalPath
                    + " selects SINKHOLE_GROTTO but no enabled river dimension reaches this "
                    + resourceType.toLowerCase() + ".");
        }
    }

    private static Set<String> referencedSurfaceBiomes(File packFolder, Set<String> regionKeys) {
        Set<String> biomes = new HashSet<>();
        File regionsFolder = new File(packFolder, "regions");
        for (String regionKey : regionKeys) {
            JSONObject region = PackValidationIo.readJson(new File(regionsFolder, regionKey + ".json"));
            if (region == null) {
                continue;
            }
            collectBiomeKeys(region.optJSONArray("landBiomes"), biomes);
            collectBiomeKeys(region.optJSONArray("seaBiomes"), biomes);
            collectBiomeKeys(region.optJSONArray("shoreBiomes"), biomes);
        }
        Set<String> roots = Set.copyOf(biomes);
        for (String biomeKey : roots) {
            collectBiomeChildren(packFolder, biomeKey, 0, biomes);
        }
        return biomes;
    }

    private static void collectBiomeChildren(File packFolder, String biomeKey, int depth, Set<String> biomes) {
        if (depth >= 4) {
            return;
        }
        JSONObject biome = PackValidationIo.readJson(new File(packFolder, "biomes/" + biomeKey + ".json"));
        if (biome == null) {
            return;
        }
        JSONArray children = biome.optJSONArray("children");
        if (children == null) {
            return;
        }
        for (int index = 0; index < children.length(); index++) {
            String child = children.optString(index, null);
            if (child == null || child.isBlank()) {
                continue;
            }
            boolean added = biomes.add(child);
            if (added) {
                collectBiomeChildren(packFolder, child, depth + 1, biomes);
            }
        }
    }

    private static Set<String> referencedKeys(JSONArray keys) {
        Set<String> referenced = new HashSet<>();
        collectBiomeKeys(keys, referenced);
        return Set.copyOf(referenced);
    }

    private static void collectBiomeKeys(JSONArray keys, Set<String> destination) {
        if (keys == null) {
            return;
        }
        for (int index = 0; index < keys.length(); index++) {
            String key = keys.optString(index, null);
            if (key != null && !key.isBlank()) {
                destination.add(key);
            }
        }
    }

    private static boolean disabled(JSONObject dimension, String flag) {
        JSONArray disabled = dimension.optJSONArray("disabledComponents");
        if (disabled == null) {
            return false;
        }
        for (int index = 0; index < disabled.length(); index++) {
            if (flag.equals(disabled.optString(index, null))) {
                return true;
            }
        }
        return false;
    }

    private static void addDistinct(List<String> destination, String value) {
        if (!destination.contains(value)) {
            destination.add(value);
        }
    }

    private static void validateOverrides(File packFolder, File resourceFolder, String resourceType,
                                          List<DimensionRiverContext> contexts,
                                          List<String> errors, List<String> warnings) {
        if (!resourceFolder.isDirectory()) {
            return;
        }
        List<File> files = PackValidationIo.listJsonRecursive(resourceFolder);
        files.sort(Comparator.comparing(File::getPath));
        for (File file : files) {
            JSONObject resource = PackValidationIo.readJson(file);
            if (resource == null || !resource.has("riverOverride")) {
                continue;
            }
            String key = PackValidationIo.deriveKey(resourceFolder, file);
            String path = resourceType + " '" + key + "' riverOverride";
            Object rawOverride = resource.opt("riverOverride");
            if (rawOverride == JSONObject.NULL) {
                continue;
            }
            if (!(rawOverride instanceof JSONObject override)) {
                errors.add(path + " must be an object or null.");
                continue;
            }
            validateOverride(packFolder, path, key, resourceType, override, contexts, errors, warnings);
        }
    }

    private static void validateOverride(File packFolder, String path, String resourceKey, String resourceType,
                                         JSONObject override, List<DimensionRiverContext> contexts,
                                         List<String> errors, List<String> warnings) {
        PackJsonFieldChecks.validateOptionalBoolean(path, override, "allowSources", errors);
        PackJsonFieldChecks.validateOptionalEnum(path, override, "routingPolicy", ROUTING_POLICIES, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, override, "routingCostMultiplier", 0D, 64D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, override, "widthMultiplier", 0.0001D, 16D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, override, "bankWidthMultiplier", 0D, 16D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, override, "depthMultiplier", 0.0001D, 16D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, override, "maxIncisionMultiplier", 0D, 16D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, override, "continuationChanceMultiplier", 0D, 16D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, override, "caveEntryMultiplier", 0D, 16D, errors);
        PackJsonFieldChecks.validateOptionalEnum(path, override, "terminalMode", TERMINAL_MODES, errors);
        validateBiomePool(packFolder, path, override, "channelBiomes", RiverBiomeRole.CHANNEL, true, errors, warnings);
        validateBiomePool(packFolder, path, override, "bankBiomes", RiverBiomeRole.BANK, true, errors, warnings);
        validateBiomePool(packFolder, path, override, "mouthBiomes", RiverBiomeRole.MOUTH, true, errors, warnings);
        validateBiomePool(packFolder, path, override, "dryBiomes", RiverBiomeRole.DRY, true, errors, warnings);
        validateBiomePool(packFolder, path, override, "floodedCaveBiomes", RiverBiomeRole.FLOODED_CAVE, true,
                errors, warnings);
        if ("SINKHOLE_GROTTO".equals(stringValue(override, "terminalMode", null))) {
            validateOverrideSinkhole(
                    packFolder,
                    path + ".terminalMode",
                    resourceKey,
                    resourceType,
                    contexts,
                    errors,
                    warnings
            );
        }
    }

    private static void validateBiomePools(File packFolder, String path, JSONObject biomes, boolean allowNull,
                                           List<String> errors, List<String> warnings) {
        validateStyle(packFolder, biomes, "selectionStyle", path, errors);
        validateBiomePool(packFolder, path, biomes, "channel", RiverBiomeRole.CHANNEL, allowNull, errors, warnings);
        validateBiomePool(packFolder, path, biomes, "bank", RiverBiomeRole.BANK, allowNull, errors, warnings);
        validateBiomePool(packFolder, path, biomes, "mouth", RiverBiomeRole.MOUTH, allowNull, errors, warnings);
        validateBiomePool(packFolder, path, biomes, "dry", RiverBiomeRole.DRY, allowNull, errors, warnings);
        validateBiomePool(packFolder, path, biomes, "floodedCave", RiverBiomeRole.FLOODED_CAVE, allowNull,
                errors, warnings);
    }

    private static void validateBiomePool(File packFolder, String path, JSONObject owner, String field,
                                          RiverBiomeRole role, boolean allowNull,
                                          List<String> errors, List<String> warnings) {
        if (!owner.has(field)) {
            return;
        }
        Object rawPool = owner.opt(field);
        if (rawPool == JSONObject.NULL && allowNull) {
            return;
        }
        if (!(rawPool instanceof JSONArray pool)) {
            errors.add(path + "." + field + " must be an array" + (allowNull ? " or null" : "") + ".");
            return;
        }

        Set<String> seen = new HashSet<>();
        File biomesFolder = new File(packFolder, "biomes");
        for (int index = 0; index < pool.length(); index++) {
            Object rawKey = pool.opt(index);
            String entryPath = path + "." + field + "[" + index + "]";
            if (!(rawKey instanceof String key) || key.isBlank()) {
                errors.add(entryPath + " must name a biome resource.");
                continue;
            }
            if (!seen.add(key)) {
                warnings.add(entryPath + " duplicates biome '" + key + "' in the same river pool.");
                continue;
            }
            File biomeFile = new File(biomesFolder, key + ".json");
            if (!biomeFile.isFile()) {
                errors.add(entryPath + " references missing biome '" + key + "'.");
                continue;
            }
            validateBiomeSuitability(entryPath, key, role, PackValidationIo.readJson(biomeFile), warnings);
        }
    }

    private static void validateBiomeSuitability(String path, String biomeKey, RiverBiomeRole role,
                                                  JSONObject biome, List<String> warnings) {
        if (biome == null || role == RiverBiomeRole.DRY || role == RiverBiomeRole.FLOODED_CAVE) {
            return;
        }
        String derivative = stringValue(biome, "vanillaDerivative", null);
        if (derivative == null || derivative.isBlank()) {
            derivative = stringValue(biome, "derivative", "minecraft:the_void");
        }
        String normalized = derivative.indexOf(':') >= 0 ? derivative : "minecraft:" + derivative;
        if (!normalized.startsWith("minecraft:")) {
            return;
        }
        boolean suitable = switch (role) {
            case CHANNEL, MOUTH -> normalized.contains("ocean") || normalized.endsWith("river");
            case BANK -> normalized.endsWith("beach") || normalized.endsWith("shore");
            default -> true;
        };
        if (!suitable) {
            warnings.add(path + " assigns biome '" + biomeKey + "' the inferred river role " + role.label
                    + " but its vanilla derivative '" + normalized
                    + "' does not match that role; native structure selection will use Iris's safe role fallback.");
        }
    }

    private static void validateNoiseChance(File packFolder, JSONObject owner, String field, String path,
                                            List<String> errors) {
        if (!owner.has(field)) {
            return;
        }
        JSONObject chance = requireObject(owner, field, path + "." + field, errors);
        if (chance == null) {
            return;
        }
        String chancePath = path + "." + field;
        PackJsonFieldChecks.validateOptionalDoubleRange(chancePath, chance, "chance", 0D, 1D, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(chancePath, chance, "influence", 0D, 1D, errors);
        validateStyle(packFolder, chance, "style", chancePath, errors);
    }

    private static void validateStyledRange(File packFolder, JSONObject owner, String field, String path,
                                            double minimum, double maximum,
                                            List<String> errors, List<String> warnings) {
        if (!owner.has(field)) {
            return;
        }
        String rangePath = path + "." + field;
        JSONObject range = resolveObject(packFolder, owner.opt(field), "snippet/style-range/", rangePath, errors);
        if (range == null) {
            return;
        }
        boolean hasMinimum = range.has("min") && range.opt("min") != JSONObject.NULL;
        boolean hasMaximum = range.has("max") && range.opt("max") != JSONObject.NULL;
        if (!hasMinimum && !hasMaximum) {
            errors.add(rangePath + " must set min and max explicitly.");
        } else if (!hasMinimum || !hasMaximum) {
            warnings.add(rangePath
                    + " should set both min and max explicitly; the omitted bound uses the shared style-range default.");
        }
        PackJsonFieldChecks.validateOptionalDoubleRange(rangePath, range, "min", minimum, maximum, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(rangePath, range, "max", minimum, maximum, errors);
        double minimumValue = doubleValue(range, "min", 16D);
        double maximumValue = doubleValue(range, "max", 32D);
        if (Double.isFinite(minimumValue) && Double.isFinite(maximumValue) && minimumValue > maximumValue) {
            errors.add(rangePath + ".min must not exceed " + rangePath + ".max.");
        }
        validateStyle(packFolder, range, "style", rangePath, errors);
    }

    private static void validateStyle(File packFolder, JSONObject owner, String field, String path,
                                      List<String> errors) {
        if (!owner.has(field)) {
            return;
        }
        validateStyle(packFolder, owner.opt(field), path + "." + field, errors, new HashSet<>());
    }

    private static void validateStyle(File packFolder, Object rawStyle, String path,
                                      List<String> errors, Set<String> dependencyStack) {
        String styleMarker = rawStyle instanceof String reference ? "style:" + reference : null;
        if (styleMarker != null && !dependencyStack.add(styleMarker)) {
            errors.add(path + " has a cyclic river-noise style snippet dependency.");
            return;
        }
        try {
            JSONObject style = resolveObject(packFolder, rawStyle, "snippet/style/", path, errors);
            if (style != null) {
                validateResolvedStyle(packFolder, style, path, errors, dependencyStack);
            }
        } finally {
            if (styleMarker != null) {
                dependencyStack.remove(styleMarker);
            }
        }
    }

    private static void validateResolvedStyle(File packFolder, JSONObject style, String path,
                                              List<String> errors, Set<String> dependencyStack) {
        PackJsonFieldChecks.validateOptionalEnum(path, style, "style", NOISE_STYLES, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, style, "cellularFrequency", 0D, Double.MAX_VALUE, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, style, "cellularZoom", 0.00001D, Double.MAX_VALUE, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, style, "zoom", 0.00001D, Double.MAX_VALUE, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, style, "multiplier",
                -Double.MAX_VALUE, Double.MAX_VALUE, errors);
        PackJsonFieldChecks.validateOptionalDoubleRange(path, style, "exponent", 0.01562D, 64D, errors);
        PackJsonFieldChecks.validateOptionalIntegerRange(path, style, "cacheSize", 0, 8192, errors);

        if (style.has("expression") && style.opt("expression") != JSONObject.NULL) {
            Object rawExpression = style.opt("expression");
            if (!(rawExpression instanceof String expressionKey) || expressionKey.isBlank()) {
                errors.add(path + ".expression must name an expression resource.");
            } else {
                validateExpression(packFolder, expressionKey, path, errors, dependencyStack);
            }
        }
        if (style.has("fracture") && style.opt("fracture") != JSONObject.NULL) {
            validateStyle(packFolder, style.opt("fracture"), path + ".fracture", errors, dependencyStack);
        }
    }

    private static void validateExpression(File packFolder, String expressionKey, String usePath,
                                           List<String> errors, Set<String> dependencyStack) {
        String expressionMarker = "expression:" + expressionKey;
        if (!dependencyStack.add(expressionMarker)) {
            errors.add(usePath + " has a cyclic river-noise expression dependency through '" + expressionKey + "'.");
            return;
        }
        try {
            File expressionFile = new File(new File(packFolder, "expressions"), expressionKey + ".json");
            JSONObject expression = PackValidationIo.readJson(expressionFile);
            if (expression == null) {
                return;
            }
            scanExpressionEntries(packFolder, expression, "variables", expressionKey, usePath, errors, dependencyStack);
            scanExpressionEntries(packFolder, expression, "functions", expressionKey, usePath, errors, dependencyStack);
        } finally {
            dependencyStack.remove(expressionMarker);
        }
    }

    private static void scanExpressionEntries(File packFolder, JSONObject expression, String field,
                                              String expressionKey, String usePath,
                                              List<String> errors, Set<String> dependencyStack) {
        JSONArray entries = expression.optJSONArray(field);
        if (entries == null) {
            return;
        }
        for (int index = 0; index < entries.length(); index++) {
            Object rawEntry = entries.opt(index);
            String snippetFolder = "variables".equals(field)
                    ? "snippet/expression-load/"
                    : "snippet/expression-function/";
            JSONObject entry = resolveExpressionEntry(packFolder, rawEntry, snippetFolder);
            if (entry == null) {
                continue;
            }
            String stream = stringValue(entry, "engineStreamValue", null);
            if (stream != null && isUnsafeRiverStream(stream)) {
                errors.add(usePath + " uses expression '" + expressionKey + "' " + field + "[" + index
                        + "].engineStreamValue '" + stream
                        + "', which depends on final river-shaped terrain and would recurse during river generation.");
            }
            if (entry.has("styleValue")) {
                validateStyle(packFolder, entry.opt("styleValue"), usePath + " -> expression '" + expressionKey
                        + "' " + field + "[" + index + "].styleValue", errors, dependencyStack);
            }
        }
    }

    private static JSONObject resolveExpressionEntry(File packFolder, Object rawEntry, String snippetFolder) {
        if (rawEntry instanceof JSONObject entry) {
            return entry;
        }
        if (!(rawEntry instanceof String reference) || !reference.startsWith("snippet/")) {
            return null;
        }
        String resolved = reference.startsWith(snippetFolder)
                ? reference
                : snippetFolder + reference.substring("snippet/".length());
        return PackValidationIo.readJson(new File(packFolder, resolved + ".json"));
    }

    private static boolean isUnsafeRiverStream(String stream) {
        return UNSAFE_RIVER_STREAMS.contains(stream) || stream.startsWith("RIVER_");
    }

    private static Set<String> noiseStyles() {
        Set<String> styles = new HashSet<>();
        for (NoiseStyle style : NoiseStyle.values()) {
            styles.add(style.name());
        }
        return Set.copyOf(styles);
    }

    private static JSONObject nestedObject(JSONObject owner, String field, String path, List<String> errors) {
        if (!owner.has(field)) {
            return new JSONObject();
        }
        return requireObject(owner, field, path + "." + field, errors);
    }

    private static JSONObject requireObject(JSONObject owner, String field, String path, List<String> errors) {
        Object raw = owner.opt(field);
        if (!(raw instanceof JSONObject object)) {
            errors.add(path + " must be an object.");
            return null;
        }
        return object;
    }

    private static JSONObject resolveObject(File packFolder, Object raw, String snippetFolder,
                                            String path, List<String> errors) {
        if (raw instanceof JSONObject object) {
            return object;
        }
        if (raw instanceof String reference && reference.startsWith("snippet/")) {
            String resolved = reference.startsWith(snippetFolder)
                    ? reference
                    : snippetFolder + reference.substring("snippet/".length());
            return PackValidationIo.readJson(new File(packFolder, resolved + ".json"));
        }
        errors.add(path + " must be an object or snippet reference.");
        return null;
    }

    private static boolean booleanValue(JSONObject object, String field, boolean defaultValue) {
        Object raw = object.opt(field);
        return raw instanceof Boolean value ? value : defaultValue;
    }

    private static int integerValue(JSONObject object, String field, int defaultValue) {
        Object raw = object.opt(field);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            return defaultValue;
        }
        return number.intValue();
    }

    private static double doubleValue(JSONObject object, String field, double defaultValue) {
        Object raw = object.opt(field);
        return raw instanceof Number number ? number.doubleValue() : defaultValue;
    }

    private static String stringValue(JSONObject object, String field, String defaultValue) {
        Object raw = object.opt(field);
        return raw instanceof String value ? value : defaultValue;
    }

    private static double noiseChanceValue(JSONObject owner, String field, double defaultValue) {
        JSONObject chance = owner.optJSONObject(field);
        return chance == null ? defaultValue : doubleValue(chance, "chance", defaultValue);
    }

    record Validation(List<String> errors, List<String> warnings) {
        Validation {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }

    private record DimensionRiverContext(
            String dimensionKey,
            JSONObject dimension,
            JSONObject rivers,
            Set<String> regionKeys
    ) {
    }

    private enum RiverBiomeRole {
        CHANNEL("SEA"),
        BANK("SHORE"),
        MOUTH("SEA"),
        DRY("LAND"),
        FLOODED_CAVE("CAVE");

        private final String label;

        RiverBiomeRole(String label) {
            this.label = label;
        }
    }
}
