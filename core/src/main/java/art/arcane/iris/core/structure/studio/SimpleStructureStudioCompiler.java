/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.structure.studio;

import art.arcane.iris.core.structure.authoring.StructureBackend;
import art.arcane.iris.core.structure.authoring.StructureCapability;
import art.arcane.iris.core.structure.authoring.StructureResourceBundle;
import art.arcane.iris.core.structure.authoring.StructureSource;
import art.arcane.iris.engine.framework.structure.StructureGraphCompilation;
import art.arcane.iris.engine.framework.structure.StructureGraphCompiler;
import art.arcane.iris.engine.framework.structure.StructureGraphDiagnostic;
import art.arcane.iris.engine.framework.structure.StructureGraphResolver;
import art.arcane.iris.engine.object.IrisDirection;
import art.arcane.iris.engine.object.IrisJigsawConnector;
import art.arcane.iris.engine.object.IrisJigsawPiece;
import art.arcane.iris.engine.object.IrisJigsawPieceEntry;
import art.arcane.iris.engine.object.IrisJigsawPool;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisPosition;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.JigsawJoint;
import art.arcane.volmlib.util.collection.KList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class SimpleStructureStudioCompiler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SimpleStructureStudioCompiler() {
    }

    public static StructureResourceBundle compile(
            SimpleStructureStudioDraft draft,
            SimpleStructureStudioPublishConfig config,
            Map<SimpleStructureStudioVariantKey, IrisObject> resolvedVariants
    ) throws IOException {
        CompilationInput input = new CompilationInput(draft, config, resolvedVariants);
        return new CompilationState(input).compile();
    }

    private static final class CompilationState {
        private final SimpleStructureStudioDraft draft;
        private final SimpleStructureStudioPublishConfig config;
        private final Map<SimpleStructureStudioVariantKey, IrisObject> resolvedVariants;
        private final Map<String, IrisJigsawPool> pools;
        private final Map<String, IrisJigsawPiece> pieces;
        private final Map<String, IrisObject> objects;
        private final List<PieceEntry> startEntries;
        private final List<PieceEntry> mainEntries;
        private final List<PieceEntry> terminalEntries;
        private final String startPoolKey;
        private final String mainPoolKey;
        private final String terminalPoolKey;

        private CompilationState(CompilationInput input) {
            draft = input.draft();
            config = input.config();
            resolvedVariants = input.resolvedVariants();
            pools = new LinkedHashMap<>();
            pieces = new LinkedHashMap<>();
            objects = new LinkedHashMap<>();
            startEntries = new ArrayList<>();
            mainEntries = new ArrayList<>();
            terminalEntries = new ArrayList<>();
            startPoolKey = config.resourceKey() + "/start";
            mainPoolKey = config.resourceKey() + "/main";
            terminalPoolKey = config.resourceKey() + "/terminal";
        }

        private StructureResourceBundle compile() throws IOException {
            validateDraft();
            validateResolvedVariants();
            compilePieces();
            compilePools();
            IrisStructure structure = compileStructure();
            validateGraph(structure);
            return bundle(structure);
        }

        private void validateDraft() {
            if (!draft.hasContent()) {
                throw new IllegalStateException("A Studio structure must contain authored tiles");
            }

            TreeSet<String> startChannels = new TreeSet<>();
            TreeSet<String> mainChannels = new TreeSet<>();
            TreeSet<String> terminalChannels = new TreeSet<>();
            for (SimpleStructureStudioCell cell : draft.cells()) {
                if (cell.rotationPolicy() == SimpleStructureStudioRotationPolicy.HALF_TURNS) {
                    throw new IllegalStateException(
                            "HALF_TURNS cannot be represented by the Iris jigsaw rotatable contract at cell "
                                    + cell.x() + ", " + cell.z()
                    );
                }
                if (cell.variants().isEmpty()) {
                    throw new IllegalStateException(
                            "Studio cell " + cell.x() + ", " + cell.z() + " has no captured variants"
                    );
                }
                channelsFor(cell.topology(), startChannels, mainChannels, terminalChannels).add(
                        cell.connectorChannel()
                );
            }

            requireChannels("START", startChannels);
            requireChannels("main", mainChannels);
            requireChannels("TERMINAL", terminalChannels);
            if (!startChannels.equals(mainChannels) || !startChannels.equals(terminalChannels)) {
                throw new IllegalStateException(
                        "START, main, and TERMINAL tiles must cover the same connector channels: start="
                                + startChannels + ", main=" + mainChannels + ", terminal=" + terminalChannels
                );
            }
        }

        private Set<String> channelsFor(
                SimpleStructureStudioTopology topology,
                Set<String> startChannels,
                Set<String> mainChannels,
                Set<String> terminalChannels
        ) {
            return switch (topology) {
                case START -> startChannels;
                case TERMINAL -> terminalChannels;
                case EMPTY -> throw new IllegalStateException("Drafts cannot publish empty cells");
                default -> mainChannels;
            };
        }

        private void requireChannels(String category, Set<String> channels) {
            if (channels.isEmpty()) {
                throw new IllegalStateException("A Studio structure must contain at least one " + category + " tile");
            }
        }

        private void validateResolvedVariants() {
            LinkedHashSet<SimpleStructureStudioVariantKey> expected = new LinkedHashSet<>();
            for (SimpleStructureStudioCell cell : draft.cells()) {
                for (SimpleStructureStudioVariant variant : cell.variants()) {
                    expected.add(SimpleStructureStudioVariantKey.of(cell, variant));
                }
            }

            for (Map.Entry<SimpleStructureStudioVariantKey, IrisObject> entry : resolvedVariants.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("Resolved Studio variants cannot contain null keys or objects");
                }
            }
            LinkedHashSet<SimpleStructureStudioVariantKey> actual = new LinkedHashSet<>(resolvedVariants.keySet());
            if (!actual.equals(expected)) {
                LinkedHashSet<SimpleStructureStudioVariantKey> missing = new LinkedHashSet<>(expected);
                missing.removeAll(actual);
                LinkedHashSet<SimpleStructureStudioVariantKey> unexpected = new LinkedHashSet<>(actual);
                unexpected.removeAll(expected);
                throw new IllegalStateException(
                        "Resolved Studio variants do not match the draft: missing=" + describe(missing)
                                + ", unexpected=" + describe(unexpected)
                );
            }
        }

        private List<String> describe(Set<SimpleStructureStudioVariantKey> keys) {
            TreeSet<String> descriptions = new TreeSet<>();
            for (SimpleStructureStudioVariantKey key : keys) {
                descriptions.add(key.cellX() + "," + key.cellZ() + ":" + key.variantId());
            }
            return List.copyOf(descriptions);
        }

        private void compilePieces() {
            for (SimpleStructureStudioCell cell : draft.cells()) {
                for (SimpleStructureStudioVariant variant : cell.variants()) {
                    SimpleStructureStudioVariantKey variantKey = SimpleStructureStudioVariantKey.of(cell, variant);
                    IrisObject object = resolvedVariants.get(variantKey);
                    validateObject(variantKey, object);
                    String resourceKey = variantResourceKey(cell, variant);
                    IrisJigsawPiece piece = new IrisJigsawPiece()
                            .setObject(resourceKey)
                            .setConnectors(connectors(cell, object))
                            .setRotatable(cell.rotationPolicy() == SimpleStructureStudioRotationPolicy.QUARTER_TURNS);
                    objects.put(resourceKey, object);
                    pieces.put(resourceKey, piece);
                    entriesFor(cell.topology()).add(new PieceEntry(resourceKey, variant.weight()));
                }
            }
        }

        private void validateObject(SimpleStructureStudioVariantKey key, IrisObject object) {
            SimpleStructureStudioLayout layout = draft.layout();
            if (object.getW() != layout.cellWidth()
                    || object.getH() != layout.captureHeight()
                    || object.getD() != layout.cellDepth()) {
                throw new IllegalStateException(
                        "Resolved object " + key.cellX() + "," + key.cellZ() + ":" + key.variantId()
                                + " has dimensions " + object.getW() + "x" + object.getH() + "x" + object.getD()
                                + "; expected " + layout.cellWidth() + "x" + layout.captureHeight() + "x"
                                + layout.cellDepth()
                );
            }
        }

        private String variantResourceKey(
                SimpleStructureStudioCell cell,
                SimpleStructureStudioVariant variant
        ) {
            return config.resourceKey() + "/cells/" + cell.x() + "-" + cell.z() + "/" + variant.id();
        }

        private KList<IrisJigsawConnector> connectors(SimpleStructureStudioCell cell, IrisObject object) {
            KList<IrisJigsawConnector> connectors = new KList<>();
            for (SimpleStructureStudioDirection direction : SimpleStructureStudioDirection.values()) {
                if (!cell.connects(direction)) {
                    continue;
                }
                connectors.add(new IrisJigsawConnector()
                        .setPosition(connectorPosition(direction, cell.connectorHeight(), object))
                        .setDirection(irisDirection(direction))
                        .setPool(mainPoolKey)
                        .setName(cell.connectorChannel())
                        .setTargetName(cell.connectorChannel())
                        .setJoint(JigsawJoint.ALIGNED));
            }
            return connectors;
        }

        private IrisPosition connectorPosition(
                SimpleStructureStudioDirection direction,
                int height,
                IrisObject object
        ) {
            return switch (direction) {
                case NORTH -> new IrisPosition(object.getW() / 2, height, 0);
                case EAST -> new IrisPosition(object.getW() - 1, height, object.getD() / 2);
                case SOUTH -> new IrisPosition(object.getW() / 2, height, object.getD() - 1);
                case WEST -> new IrisPosition(0, height, object.getD() / 2);
            };
        }

        private IrisDirection irisDirection(SimpleStructureStudioDirection direction) {
            return switch (direction) {
                case NORTH -> IrisDirection.NORTH_NEGATIVE_Z;
                case EAST -> IrisDirection.EAST_POSITIVE_X;
                case SOUTH -> IrisDirection.SOUTH_POSITIVE_Z;
                case WEST -> IrisDirection.WEST_NEGATIVE_X;
            };
        }

        private List<PieceEntry> entriesFor(SimpleStructureStudioTopology topology) {
            return switch (topology) {
                case START -> startEntries;
                case TERMINAL -> terminalEntries;
                case EMPTY -> throw new IllegalStateException("Drafts cannot publish empty cells");
                default -> mainEntries;
            };
        }

        private void compilePools() {
            pools.put(startPoolKey, pool(startEntries, ""));
            pools.put(mainPoolKey, pool(mainEntries, terminalPoolKey));
            pools.put(terminalPoolKey, pool(terminalEntries, ""));
        }

        private IrisJigsawPool pool(List<PieceEntry> entries, String fallback) {
            KList<IrisJigsawPieceEntry> weightedPieces = new KList<>();
            for (PieceEntry entry : entries) {
                weightedPieces.add(new IrisJigsawPieceEntry(entry.pieceKey(), entry.weight()));
            }
            return new IrisJigsawPool().setPieces(weightedPieces).setFallback(fallback);
        }

        private IrisStructure compileStructure() {
            IrisStructure structure = new IrisStructure()
                    .setStartPool(startPoolKey)
                    .setMaxDepth(config.maxDepth())
                    .setMaxSizeChunks(config.maxSizeChunks())
                    .setPlaceMode(config.placeMode());
            structure.setLoadKey(config.resourceKey());
            return structure;
        }

        private void validateGraph(IrisStructure structure) {
            StructureGraphCompilation compilation = StructureGraphCompiler.compile(
                    structure,
                    new BundleGraphResolver(pools, pieces, objects)
            );
            if (compilation.isAssemblyViable() && compilation.getDiagnostics().isEmpty()) {
                return;
            }

            StringBuilder failure = new StringBuilder("Studio structure graph is not safely assemblable");
            for (StructureGraphDiagnostic diagnostic : compilation.getDiagnostics()) {
                failure.append("; ").append(diagnostic.code()).append(": ").append(diagnostic.message());
            }
            if (!compilation.isAssemblyViable() && compilation.getDiagnostics().isEmpty()) {
                failure.append("; deterministic assembly samples did not complete");
            }
            throw new IllegalStateException(failure.toString());
        }

        private StructureResourceBundle bundle(IrisStructure structure) throws IOException {
            StructureResourceBundle.Builder bundle = StructureResourceBundle.builder(config.structureKey())
                    .source(StructureSource.of(StructureSource.Kind.IRIS, config.structureKey()))
                    .backend(StructureBackend.IRIS_ASSEMBLY)
                    .capability(StructureCapability.BLOCKS)
                    .capability(StructureCapability.BLOCK_ENTITIES)
                    .capability(StructureCapability.CONNECTORS)
                    .capability(StructureCapability.IRIS_PLACEMENT)
                    .textResource("structures/" + config.resourceKey() + ".json", GSON.toJson(structure));

            for (Map.Entry<String, IrisObject> entry : objects.entrySet()) {
                bundle.resource("objects/" + entry.getKey() + ".iob", serialize(entry.getValue()));
            }
            for (Map.Entry<String, IrisJigsawPiece> entry : pieces.entrySet()) {
                bundle.textResource("jigsaw-pieces/" + entry.getKey() + ".json", GSON.toJson(entry.getValue()));
            }
            for (Map.Entry<String, IrisJigsawPool> entry : pools.entrySet()) {
                bundle.textResource("jigsaw-pools/" + entry.getKey() + ".json", GSON.toJson(entry.getValue()));
            }
            return bundle.build();
        }

        private byte[] serialize(IrisObject object) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            object.write(output);
            return output.toByteArray();
        }
    }

    private record CompilationInput(
            SimpleStructureStudioDraft draft,
            SimpleStructureStudioPublishConfig config,
            Map<SimpleStructureStudioVariantKey, IrisObject> resolvedVariants
    ) {
        private CompilationInput {
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(resolvedVariants, "resolvedVariants");
        }
    }

    private record PieceEntry(String pieceKey, int weight) {
    }

    private static final class BundleGraphResolver implements StructureGraphResolver {
        private final Map<String, IrisJigsawPool> pools;
        private final Map<String, IrisJigsawPiece> pieces;
        private final Map<String, IrisObject> objects;

        private BundleGraphResolver(
                Map<String, IrisJigsawPool> pools,
                Map<String, IrisJigsawPiece> pieces,
                Map<String, IrisObject> objects
        ) {
            this.pools = pools;
            this.pieces = pieces;
            this.objects = objects;
        }

        @Override
        public IrisJigsawPool loadPool(String key) {
            return pools.get(key);
        }

        @Override
        public IrisJigsawPiece loadPiece(String key) {
            return pieces.get(key);
        }

        @Override
        public IrisObject loadObject(String key) {
            return objects.get(key);
        }
    }
}
