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

package art.arcane.iris.modded.structure;

import art.arcane.iris.core.structure.authoring.StructureBackend;
import art.arcane.iris.core.structure.authoring.StructureCapability;
import art.arcane.iris.core.structure.authoring.StructureKey;
import art.arcane.iris.core.structure.authoring.StructureLoss;
import art.arcane.iris.core.structure.authoring.StructureResourceBundle;
import art.arcane.iris.core.structure.authoring.StructureSource;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.TileData;
import art.arcane.iris.util.common.math.IrisBlockVector;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ModdedJigsawStructureCapture {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final MinecraftServer server;
    private final StructureKey sourceKey;
    private final StructureKey targetKey;
    private final StructureSource.Kind sourceKind;
    private final Registry<Structure> structureRegistry;
    private final Registry<StructureTemplatePool> poolRegistry;
    private final Registry<Block> blockRegistry;
    private final StructureTemplateManager templateManager;
    private final RegistryOps<Tag> registryOps;
    private final Map<ResourceKey<StructureTemplatePool>, ResourceKey<StructureTemplatePool>> aliases;
    private final Map<String, byte[]> objects;
    private final Map<String, Map<String, Object>> pieces;
    private final Map<String, Map<String, Object>> pools;
    private final EnumSet<StructureCapability> capabilities;
    private final List<StructureLoss> losses;
    private final Set<String> recordedLosses;
    private final Set<String> visitedPools;
    private final Deque<String> pendingPools;
    private int blocks;

    private ModdedJigsawStructureCapture(
            MinecraftServer server,
            StructureKey sourceKey,
            StructureKey targetKey,
            StructureSource.Kind sourceKind
    ) {
        this.server = Objects.requireNonNull(server);
        this.sourceKey = Objects.requireNonNull(sourceKey);
        this.targetKey = Objects.requireNonNull(targetKey);
        this.sourceKind = Objects.requireNonNull(sourceKind);
        structureRegistry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        poolRegistry = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);
        blockRegistry = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        templateManager = server.getStructureManager();
        registryOps = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        aliases = new HashMap<>();
        objects = new LinkedHashMap<>();
        pieces = new LinkedHashMap<>();
        pools = new LinkedHashMap<>();
        capabilities = EnumSet.of(
                StructureCapability.BLOCKS,
                StructureCapability.CONNECTORS,
                StructureCapability.IRIS_PLACEMENT
        );
        losses = new ArrayList<>();
        recordedLosses = new HashSet<>();
        visitedPools = new HashSet<>();
        pendingPools = new ArrayDeque<>();
    }

    static Capture capture(
            MinecraftServer server,
            StructureKey sourceKey,
            StructureKey targetKey,
            StructureSource.Kind sourceKind
    ) throws IOException {
        return new ModdedJigsawStructureCapture(server, sourceKey, targetKey, sourceKind).capture();
    }

    private Capture capture() throws IOException {
        Identifier sourceIdentifier = identifier(sourceKey);
        Structure structure = structureRegistry.getValue(sourceIdentifier);
        if (structure == null) {
            throw new IllegalArgumentException("No registered structure exists for " + sourceKey);
        }
        if (!(structure instanceof JigsawStructure jigsaw)) {
            throw new UnsupportedStructureTypeException("Structure " + sourceKey + " uses "
                    + structure.getClass().getSimpleName() + "; only jigsaw structure graphs can be converted to Iris assembly resources");
        }

        CompoundTag encodedStructure = encodeStructure(structure);
        configureAliases(jigsaw);
        recordRootLosses(jigsaw);
        String startPoolKey = poolKey(jigsaw.getStartPool().value());
        pendingPools.add(startPoolKey);
        while (!pendingPools.isEmpty()) {
            capturePool(pendingPools.removeFirst());
        }
        if (pieces.isEmpty()) {
            throw new IllegalStateException("Structure " + sourceKey + " produced no importable pieces");
        }

        int maxDepth = Math.max(1, encodedStructure.getIntOr("size", 1));
        int maxDistance = readHorizontalDistance(encodedStructure);
        StructureSource source = StructureSource.identified(
                sourceKind,
                sourceKey,
                SharedConstants.getCurrentVersion().name(),
                NbtUtils.structureToSnbt(encodedStructure).getBytes(StandardCharsets.UTF_8)
        );
        StructureResourceBundle bundle = buildBundle(source, startPoolKey, maxDepth, maxDistance);
        return new Capture(bundle, blocks, pieces.size(), pools.size());
    }

    private CompoundTag encodeStructure(Structure structure) {
        Tag encoded = Structure.DIRECT_CODEC.encodeStart(registryOps, structure).getOrThrow();
        if (!(encoded instanceof CompoundTag compound)) {
            throw new IllegalStateException("Structure codec did not produce a compound for " + sourceKey);
        }
        return compound;
    }

    private void configureAliases(JigsawStructure structure) {
        List<PoolAliasBinding> bindings = structure.getPoolAliases();
        if (bindings.isEmpty()) {
            return;
        }
        RandomSource random = RandomSource.create(stableSeed(sourceKey.value()));
        for (PoolAliasBinding binding : bindings) {
            binding.forEachResolved(random, aliases::put);
        }
        addLossOnce(
                "pool_aliases_resolved_once",
                StructureLoss.warning(
                        StructureCapability.CONNECTORS,
                        "pool_aliases_resolved_once",
                        bindings.size() + " native pool alias binding(s) were resolved deterministically for the imported graph; per-placement alias variation is not represented."
                )
        );
    }

    private void recordRootLosses(JigsawStructure structure) {
        losses.add(StructureLoss.warning(
                StructureCapability.NATIVE_PLACEMENT,
                "native_placement_settings_not_imported",
                "Native start height, heightmap projection, expansion, padding, and placement-set settings are not represented by Iris assembly placement."
        ));
        losses.add(StructureLoss.warning(
                StructureCapability.LIQUID_SETTINGS,
                "native_liquid_settings_not_imported",
                "Native structure liquid placement behavior is not represented beyond the captured block and waterlogged states."
        ));
        if (structure.terrainAdaptation() != TerrainAdjustment.NONE) {
            losses.add(StructureLoss.warning(
                    StructureCapability.TERRAIN_ADAPTATION,
                    "terrain_adaptation_not_imported",
                    "Native terrain adaptation '" + structure.terrainAdaptation().getSerializedName()
                            + "' is not represented by the Iris assembly."
            ));
        }
    }

    private void capturePool(String sourcePoolKey) throws IOException {
        if (!visitedPools.add(sourcePoolKey)) {
            return;
        }
        Identifier sourcePoolIdentifier = Identifier.tryParse(sourcePoolKey);
        StructureTemplatePool pool = sourcePoolIdentifier == null ? null : poolRegistry.getValue(sourcePoolIdentifier);
        if (pool == null) {
            throw new IllegalStateException("Jigsaw graph references missing template pool " + sourcePoolKey);
        }
        String irisPoolName = poolName(targetKey.path(), sourcePoolKey);
        List<Object> entries = new ArrayList<>();
        List<Pair<StructurePoolElement, Integer>> templates = pool.getTemplates();
        for (int index = 0; index < templates.size(); index++) {
            Pair<StructurePoolElement, Integer> weighted = templates.get(index);
            StructurePoolElement element = weighted.getFirst();
            int weight = Math.max(1, weighted.getSecond());
            Map<String, Object> entry = new LinkedHashMap<>();
            if (element == EmptyPoolElement.INSTANCE) {
                entry.put("empty", true);
            } else {
                entry.put("piece", captureElement(sourcePoolKey, index, element));
            }
            entry.put("weight", weight);
            entries.add(entry);
        }

        Map<String, Object> poolJson = new LinkedHashMap<>();
        poolJson.put("pieces", entries);
        String fallback = resolvedPoolKey(pool.getFallback().value());
        if (!fallback.equals(sourcePoolKey)) {
            poolJson.put("fallback", poolName(targetKey.path(), fallback));
            pendingPools.addLast(fallback);
        }
        pools.put(irisPoolName, poolJson);
    }

    private String captureElement(String sourcePoolKey, int index, StructurePoolElement element) throws IOException {
        Objects.requireNonNull(element, "pool element");
        if (element instanceof SinglePoolElement single) {
            return captureSingle(single);
        }
        String generatedName = generatedPieceName(targetKey.path(), sourcePoolKey, index, element);
        if (pieces.containsKey(generatedName)) {
            return generatedName;
        }
        if (element instanceof ListPoolElement list) {
            capabilities.add(StructureCapability.LIST_ELEMENTS);
            CompositeCapture composite = captureList(list, generatedName);
            blocks += composite.object().getBlocks().size();
            emitPiece(generatedName, composite.object(), composite.losses(), element);
            return generatedName;
        }
        IrisObject object = emptyObject(element);
        StructureCapability unsupported = element instanceof FeaturePoolElement
                ? StructureCapability.FEATURE_ELEMENTS : StructureCapability.BLOCKS;
        StructureLoss loss = StructureLoss.warning(
                unsupported,
                "unsupported_pool_element",
                "Pool element " + element.getClass().getSimpleName() + " was represented as an empty Iris piece."
        );
        emitPiece(generatedName, object, List.of(loss), element);
        return generatedName;
    }

    private String captureSingle(SinglePoolElement element) throws IOException {
        Identifier templateIdentifier = element.getTemplateLocation();
        StructureKey templateKey = StructureKey.parse(templateIdentifier.toString());
        boolean legacy = element instanceof LegacySinglePoolElement;
        String pieceName = legacy
                ? legacyPieceName(targetKey.path(), templateKey.value())
                : pieceName(targetKey.path(), templateKey.value());
        if (pieces.containsKey(pieceName)) {
            return pieceName;
        }
        StructureTemplate template = templateManager.get(templateIdentifier)
                .orElseThrow(() -> new IllegalStateException("Missing structure template " + templateIdentifier));
        ModdedStructureTemplateCapture.Capture capture = ModdedStructureTemplateCapture.capture(
                templateKey, template, blockRegistry, true, !legacy);
        capabilities.addAll(capture.capabilities());
        blocks += capture.blocks();
        List<StructureLoss> pieceLosses = new ArrayList<>(capture.losses());
        pieceLosses.addAll(elementLosses(element));
        emitPiece(pieceName, capture.object(), pieceLosses, element);
        return pieceName;
    }

    private CompositeCapture captureList(ListPoolElement list, String pieceName) throws IOException {
        Vec3i size = list.getSize(templateManager, Rotation.NONE);
        IrisObject composite = new IrisObject(
                Math.max(1, size.getX()),
                Math.max(1, size.getY()),
                Math.max(1, size.getZ())
        );
        List<StructureLoss> compositeLosses = new ArrayList<>();
        for (StructurePoolElement child : list.getElements()) {
            if (child == EmptyPoolElement.INSTANCE) {
                continue;
            }
            if (child instanceof SinglePoolElement single) {
                Identifier templateIdentifier = single.getTemplateLocation();
                StructureTemplate template = templateManager.get(templateIdentifier)
                        .orElseThrow(() -> new IllegalStateException("Missing structure template " + templateIdentifier));
                ModdedStructureTemplateCapture.Capture capture = ModdedStructureTemplateCapture.capture(
                        StructureKey.parse(templateIdentifier.toString()),
                        template,
                        blockRegistry,
                        true,
                        !(single instanceof LegacySinglePoolElement)
                );
                merge(composite, capture.object());
                capabilities.addAll(capture.capabilities());
                compositeLosses.addAll(capture.losses());
                compositeLosses.addAll(elementLosses(single));
                continue;
            }
            if (child instanceof ListPoolElement nested) {
                CompositeCapture nestedCapture = captureList(nested, pieceName);
                merge(composite, nestedCapture.object());
                compositeLosses.addAll(nestedCapture.losses());
                continue;
            }
            StructureCapability unsupported = child instanceof FeaturePoolElement
                    ? StructureCapability.FEATURE_ELEMENTS : StructureCapability.LIST_ELEMENTS;
            compositeLosses.add(StructureLoss.warning(
                    unsupported,
                    "list_child_not_imported",
                    "List element child " + child.getClass().getSimpleName() + " could not be flattened into the Iris object."
            ));
        }
        compositeLosses.addAll(elementLosses(list));
        return new CompositeCapture(composite, compositeLosses);
    }

    private List<StructureLoss> elementLosses(StructurePoolElement element) {
        List<StructureLoss> elementLosses = new ArrayList<>();
        if (element.getProjection() != StructureTemplatePool.Projection.RIGID) {
            elementLosses.add(StructureLoss.warning(
                    StructureCapability.PROJECTION,
                    "terrain_matching_projection_not_imported",
                    "Pool projection '" + element.getProjection().getSerializedName()
                            + "' was converted to rigid Iris piece placement."
            ));
        }
        Tag encoded = StructurePoolElement.CODEC.encodeStart(registryOps, element).getOrThrow();
        if (encoded instanceof CompoundTag compound) {
            String processors = compound.getStringOr("processors", "");
            if (!processors.isEmpty() && !processors.equals("minecraft:empty")) {
                elementLosses.add(StructureLoss.warning(
                        StructureCapability.PROCESSORS,
                        "native_processors_not_imported",
                        "Native processor list '" + processors + "' was not applied to the captured template."
                ));
            }
            if (compound.contains("override_liquid_settings")) {
                elementLosses.add(StructureLoss.warning(
                        StructureCapability.LIQUID_SETTINGS,
                        "element_liquid_settings_not_imported",
                        "The pool element's liquid setting override is not represented by Iris placement."
                ));
            }
        }
        return elementLosses;
    }

    private void emitPiece(
            String pieceName,
            IrisObject object,
            List<StructureLoss> pieceLosses,
            StructurePoolElement element
    ) throws IOException {
        String objectResource = "objects/" + pieceName + ".iob";
        objects.put(pieceName, serialize(object));
        for (StructureLoss loss : pieceLosses) {
            losses.add(loss.affecting(objectResource));
        }
        List<Map<String, Object>> connectors = connectors(element, pieceName);
        Map<String, Object> pieceJson = new LinkedHashMap<>();
        pieceJson.put("object", pieceName);
        pieceJson.put("connectors", connectors);
        pieceJson.put("rotatable", true);
        pieces.put(pieceName, pieceJson);
    }

    private List<Map<String, Object>> connectors(StructurePoolElement element, String pieceName) {
        List<StructureTemplate.JigsawBlockInfo> sourceConnectors = element.getShuffledJigsawBlocks(
                templateManager,
                BlockPos.ZERO,
                Rotation.NONE,
                RandomSource.create(stableSeed(sourceKey.value() + ":" + pieceName))
        );
        List<Map<String, Object>> connectors = new ArrayList<>(sourceConnectors.size());
        for (StructureTemplate.JigsawBlockInfo source : sourceConnectors) {
            recordConnectorLosses(source, pieceName);
            ResourceKey<StructureTemplatePool> resolvedPool = aliases.getOrDefault(source.pool(), source.pool());
            String sourcePoolKey = resolvedPool.identifier().toString();
            pendingPools.addLast(sourcePoolKey);
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("x", source.info().pos().getX());
            position.put("y", source.info().pos().getY());
            position.put("z", source.info().pos().getZ());
            Map<String, Object> connector = new LinkedHashMap<>();
            connector.put("position", position);
            connector.put("direction", directionName(JigsawBlock.getFrontFacing(source.info().state())));
            connector.put("top", directionName(JigsawBlock.getTopFacing(source.info().state())));
            connector.put("pool", poolName(targetKey.path(), sourcePoolKey));
            connector.put("name", source.name().toString());
            connector.put("targetName", source.target().toString());
            connector.put("joint", source.jointType().getSerializedName().equals("aligned") ? "ALIGNED" : "ROLLABLE");
            connectors.add(connector);
        }
        return connectors;
    }

    private void recordConnectorLosses(StructureTemplate.JigsawBlockInfo connector, String pieceName) {
        String pieceResource = "jigsaw-pieces/" + pieceName + ".json";
        if (connector.placementPriority() != 0 || connector.selectionPriority() != 0) {
            addLossOnce(
                    "connector-priority:" + pieceName,
                    StructureLoss.warning(
                            StructureCapability.CONNECTORS,
                            "connector_priorities_not_imported",
                            "Native connector placement and selection priorities are not represented by Iris assembly ordering."
                    ).affecting(pieceResource)
            );
        }
    }

    private StructureResourceBundle buildBundle(
            StructureSource source,
            String startPoolKey,
            int maxDepth,
            int maxDistance
    ) {
        StructureResourceBundle.Builder bundle = StructureResourceBundle.builder(targetKey)
                .source(source)
                .backend(StructureBackend.IRIS_ASSEMBLY)
                .capabilities(capabilities)
                .losses(losses);
        for (Map.Entry<String, byte[]> entry : objects.entrySet()) {
            bundle.resource("objects/" + entry.getKey() + ".iob", entry.getValue());
        }
        for (Map.Entry<String, Map<String, Object>> entry : pieces.entrySet()) {
            bundle.textResource("jigsaw-pieces/" + entry.getKey() + ".json", GSON.toJson(entry.getValue()));
        }
        for (Map.Entry<String, Map<String, Object>> entry : pools.entrySet()) {
            bundle.textResource("jigsaw-pools/" + entry.getKey() + ".json", GSON.toJson(entry.getValue()));
        }
        bundle.textResource(
                "structures/" + targetKey.path() + ".json",
                GSON.toJson(structureJson(sourceKey.value(), poolName(targetKey.path(), startPoolKey), maxDepth, maxDistance))
        );
        return bundle.build();
    }

    private String resolvedPoolKey(StructureTemplatePool pool) {
        ResourceKey<StructureTemplatePool> raw = poolResourceKey(pool);
        return aliases.getOrDefault(raw, raw).identifier().toString();
    }

    private String poolKey(StructureTemplatePool pool) {
        return poolResourceKey(pool).identifier().toString();
    }

    private ResourceKey<StructureTemplatePool> poolResourceKey(StructureTemplatePool pool) {
        Identifier identifier = poolRegistry.getKey(pool);
        if (identifier == null) {
            throw new IllegalStateException("Jigsaw structure references an unregistered template pool");
        }
        return ResourceKey.create(Registries.TEMPLATE_POOL, identifier);
    }

    private void addLossOnce(String key, StructureLoss loss) {
        if (recordedLosses.add(key)) {
            losses.add(loss);
        }
    }

    private static void merge(IrisObject target, IrisObject source) {
        for (IrisBlockVector position : source.getBlocks().keys()) {
            int x = position.getBlockX() + source.getCenter().getX();
            int y = position.getBlockY() + source.getCenter().getY();
            int z = position.getBlockZ() + source.getCenter().getZ();
            if (x < 0 || y < 0 || z < 0 || x >= target.getW() || y >= target.getH() || z >= target.getD()) {
                continue;
            }
            target.setUnsignedTile(x, y, z, null);
            target.setUnsigned(x, y, z, source.getBlocks().get(position));
            TileData tile = source.getStates().get(position);
            if (tile != null) {
                target.setUnsignedTile(x, y, z, tile);
            }
        }
    }

    private static IrisObject emptyObject() {
        return new IrisObject(1, 1, 1);
    }

    private IrisObject emptyObject(StructurePoolElement element) {
        Vec3i size = element.getSize(templateManager, Rotation.NONE);
        return new IrisObject(
                Math.max(1, size.getX()),
                Math.max(1, size.getY()),
                Math.max(1, size.getZ())
        );
    }

    private static byte[] serialize(IrisObject object) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        object.write(output);
        return output.toByteArray();
    }

    private static int readHorizontalDistance(CompoundTag encodedStructure) {
        int scalar = encodedStructure.getIntOr("max_distance_from_center", -1);
        if (scalar > 0) {
            return scalar;
        }
        CompoundTag compound = encodedStructure.getCompoundOrEmpty("max_distance_from_center");
        return Math.max(1, compound.getIntOr("horizontal", 80));
    }

    static Map<String, Object> structureJson(String source, String startPool, int maxDepth, int maxDistance) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("startPool", startPool);
        root.put("maxDepth", Math.max(1, Math.min(30, maxDepth)));
        root.put("maxSizeChunks", Math.max(1, Math.min(32, (Math.max(1, maxDistance) + 15) / 16)));
        root.put("placeMode", "STRUCTURE_PIECE");
        root.put("vanillaSource", source);
        return root;
    }

    static String poolName(String base, String sourcePoolKey) {
        StructureKey key = StructureKey.parse(sourcePoolKey);
        return base + "/pool/" + key.namespace() + "/" + key.path();
    }

    static String pieceName(String base, String templateKey) {
        StructureKey key = StructureKey.parse(templateKey);
        return base + "/piece/" + key.namespace() + "/" + key.path();
    }

    static String legacyPieceName(String base, String templateKey) {
        StructureKey key = StructureKey.parse(templateKey);
        return base + "/piece/generated/legacy/" + key.namespace() + "/" + key.path();
    }

    static String directionName(Direction direction) {
        return switch (direction) {
            case UP -> "UP_POSITIVE_Y";
            case DOWN -> "DOWN_NEGATIVE_Y";
            case SOUTH -> "SOUTH_POSITIVE_Z";
            case EAST -> "EAST_POSITIVE_X";
            case WEST -> "WEST_NEGATIVE_X";
            case NORTH -> "NORTH_NEGATIVE_Z";
        };
    }

    private static String generatedPieceName(String base, String sourcePoolKey, int index, StructurePoolElement element) {
        StructureKey poolKey = StructureKey.parse(sourcePoolKey);
        String type = element instanceof ListPoolElement ? "list" : element instanceof FeaturePoolElement ? "feature" : "unsupported";
        return base + "/piece/generated/" + type + "/" + poolKey.namespace() + "/" + poolKey.path() + "/" + index;
    }

    private static long stableSeed(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static Identifier identifier(StructureKey key) {
        return Identifier.fromNamespaceAndPath(key.namespace(), key.path());
    }

    record Capture(StructureResourceBundle bundle, int blocks, int pieces, int pools) {
        Capture {
            Objects.requireNonNull(bundle);
        }
    }

    static final class UnsupportedStructureTypeException extends IllegalArgumentException {
        UnsupportedStructureTypeException(String message) {
            super(message);
        }
    }

    private record CompositeCapture(IrisObject object, List<StructureLoss> losses) {
        CompositeCapture {
            Objects.requireNonNull(object);
            losses = List.copyOf(losses);
        }
    }
}
