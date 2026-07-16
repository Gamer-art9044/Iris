package art.arcane.iris.engine.framework.structure;

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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

public final class StructureGraphCompiler {
    private static final int ASSEMBLY_PIECE_CAP = 512;
    private static final int MAX_DEPTH = 30;
    private static final int MAX_SIZE_CHUNKS = 32;
    private static final int[] NO_ROTATION = {0};
    private static final int[] Y_ROTATIONS = {0, 90, 180, 270};
    private static final List<Long> ASSEMBLY_SEEDS = List.of(0L, 1L, 2L, 3L, 5L, 8L, 13L, 21L);

    private StructureGraphCompiler() {
    }

    public static StructureGraphCompilation compile(IrisStructure structure, StructureGraphResolver resolver) {
        CompilationState state = new CompilationState(
                Objects.requireNonNull(structure), Objects.requireNonNull(resolver));
        state.loadClosure();
        state.detectFallbackCycles();
        state.analyzeReachability();
        List<StructureGraphAssemblySample> samples = state.sampleAssemblies();
        state.reportPieceCapSamples(samples);
        CompiledStructureGraph graph = state.buildGraph();
        return StructureGraphCompilation.builder(graph)
                .diagnostics(state.diagnostics())
                .assemblySamples(samples)
                .build();
    }

    private static final class CompilationState {
        private final IrisStructure structure;
        private final StructureGraphResolver resolver;
        private final CompiledStructureGraph.Builder graph;
        private final Deque<String> pendingPools = new ArrayDeque<>();
        private final Map<String, List<PoolReference>> poolReferences = new LinkedHashMap<>();
        private final Map<String, List<PoolEntryReference>> poolEntries = new LinkedHashMap<>();
        private final Map<String, List<ConnectorReference>> pieceConnectors = new LinkedHashMap<>();
        private final Map<String, String> pieceObjects = new LinkedHashMap<>();
        private final Set<String> processedPools = new HashSet<>();
        private final Set<String> missingPools = new HashSet<>();
        private final Set<String> attemptedPieces = new HashSet<>();
        private final Set<String> missingPieces = new HashSet<>();
        private final Set<String> attemptedObjects = new HashSet<>();
        private final Set<String> missingObjects = new HashSet<>();
        private final Set<String> reachableEntries = new LinkedHashSet<>();
        private final Set<PieceReachState> reachablePieceStates = new LinkedHashSet<>();
        private final Map<String, List<OrientedConnector>> activeConnectors = new LinkedHashMap<>();
        private final List<StructureGraphDiagnostic> diagnostics = new ArrayList<>();
        private final Set<String> diagnosticKeys = new HashSet<>();

        private CompilationState(IrisStructure structure, StructureGraphResolver resolver) {
            this.structure = structure;
            this.resolver = resolver;
            this.graph = CompiledStructureGraph.builder(structure);
        }

        private void loadClosure() {
            validateStructureLimits();
            String startPool = normalize(structure.getStartPool());
            if (startPool.isEmpty()) {
                addDiagnostic(StructureGraphDiagnostic.Code.MISSING_START_POOL,
                        "Structure '" + structureKey() + "' does not declare a start pool.",
                        "structure:start-pool:empty");
                return;
            }

            requestPool(startPool, new PoolReference(
                    "Structure '" + structureKey() + "' references missing start pool '" + startPool + "'.",
                    StructureGraphDiagnostic.Code.MISSING_START_POOL));
            while (!pendingPools.isEmpty()) {
                processPool(pendingPools.removeFirst());
            }
        }

        private void validateStructureLimits() {
            if (structure.getMaxDepth() < 1 || structure.getMaxDepth() > MAX_DEPTH) {
                addDiagnostic(StructureGraphDiagnostic.Code.INVALID_MAX_DEPTH,
                        "Structure '" + structureKey() + "' has maxDepth " + structure.getMaxDepth()
                                + "; it must be between 1 and " + MAX_DEPTH + ".",
                        "structure:max-depth");
            }
            if (structure.getMaxSizeChunks() < 1 || structure.getMaxSizeChunks() > MAX_SIZE_CHUNKS) {
                addDiagnostic(StructureGraphDiagnostic.Code.INVALID_MAX_SIZE,
                        "Structure '" + structureKey() + "' has maxSizeChunks " + structure.getMaxSizeChunks()
                                + "; it must be between 1 and " + MAX_SIZE_CHUNKS + ".",
                        "structure:max-size");
            }
        }

        private void requestPool(String rawKey, PoolReference reference) {
            String key = normalize(rawKey);
            if (key.isEmpty()) {
                return;
            }
            poolReferences.computeIfAbsent(key, ignored -> new ArrayList<>()).add(reference);
            if (missingPools.contains(key)) {
                addDiagnostic(reference.code(), reference.message(), "missing-pool:" + key + ":" + reference.message());
                return;
            }
            if (!processedPools.contains(key) && !pendingPools.contains(key)) {
                pendingPools.addLast(key);
            }
        }

        private void processPool(String key) {
            if (!processedPools.add(key)) {
                return;
            }
            IrisJigsawPool pool = resolver.loadPool(key);
            if (pool == null) {
                missingPools.add(key);
                for (PoolReference reference : poolReferences.getOrDefault(key, List.of())) {
                    addDiagnostic(reference.code(), reference.message(),
                            "missing-pool:" + key + ":" + reference.message());
                }
                return;
            }

            graph.pools().put(key, pool);
            List<PoolEntryReference> entries = new ArrayList<>();
            poolEntries.put(key, entries);
            KList<IrisJigsawPieceEntry> configuredEntries = pool.getPieces();
            if (configuredEntries == null || configuredEntries.isEmpty()) {
                if (key.equals(normalize(structure.getStartPool()))) {
                    addDiagnostic(StructureGraphDiagnostic.Code.EMPTY_START_POOL,
                            "Jigsaw pool '" + key + "' is empty.",
                            "empty-pool:" + key);
                }
            } else {
                for (int index = 0; index < configuredEntries.size(); index++) {
                    scanPoolEntry(key, index, configuredEntries.get(index), entries);
                }
            }

            String fallback = JigsawPoolSelection.directFallbackKey(pool);
            if (!fallback.isEmpty()) {
                requestPool(fallback, new PoolReference(
                        "Jigsaw pool '" + key + "' references missing fallback pool '" + fallback + "'.",
                        StructureGraphDiagnostic.Code.MISSING_POOL));
            }
        }

        private void scanPoolEntry(String poolKey, int index, IrisJigsawPieceEntry entry,
                                   List<PoolEntryReference> entries) {
            if (entry == null) {
                addDiagnostic(StructureGraphDiagnostic.Code.INVALID_POOL_ENTRY,
                        "Jigsaw pool '" + poolKey + "' pieces[" + index + "] is null.",
                        "pool-entry:null:" + poolKey + ":" + index);
                return;
            }

            String pieceKey = normalize(entry.getPiece());
            PoolEntryReference entryReference = new PoolEntryReference(
                    poolKey, index, pieceKey, entry.getWeight(), entry.isEmpty());
            entries.add(entryReference);
            if (entry.getWeight() <= 0) {
                addDiagnostic(StructureGraphDiagnostic.Code.INVALID_WEIGHT,
                        "Jigsaw pool '" + poolKey + "' pieces[" + index + "] has non-positive weight "
                                + entry.getWeight() + ".",
                        "pool-entry:weight:" + poolKey + ":" + index);
            }
            if (entry.isEmpty()) {
                if (!pieceKey.isEmpty()) {
                    addDiagnostic(StructureGraphDiagnostic.Code.INVALID_POOL_ENTRY,
                            "Jigsaw pool '" + poolKey + "' pieces[" + index
                                    + "] declares both empty=true and a piece.",
                            "pool-entry:ambiguous-empty:" + poolKey + ":" + index);
                }
                return;
            }
            if (pieceKey.isEmpty()) {
                addDiagnostic(StructureGraphDiagnostic.Code.MISSING_PIECE,
                        "Jigsaw pool '" + poolKey + "' pieces[" + index + "] does not declare a piece.",
                        "pool-entry:piece-empty:" + poolKey + ":" + index);
                return;
            }
            loadPiece(pieceKey, "Jigsaw pool '" + poolKey + "' pieces[" + index
                    + "] references missing piece '" + pieceKey + "'.");
        }

        private void loadPiece(String key, String missingMessage) {
            if (missingPieces.contains(key)) {
                addDiagnostic(StructureGraphDiagnostic.Code.MISSING_PIECE, missingMessage,
                        "missing-piece:" + key + ":" + missingMessage);
                return;
            }
            if (!attemptedPieces.add(key)) {
                return;
            }

            IrisJigsawPiece piece = resolver.loadPiece(key);
            if (piece == null) {
                missingPieces.add(key);
                addDiagnostic(StructureGraphDiagnostic.Code.MISSING_PIECE, missingMessage,
                        "missing-piece:" + key + ":" + missingMessage);
                return;
            }

            graph.pieces().put(key, piece);
            IrisObject object = loadPieceObject(key, piece);
            scanConnectors(key, piece, object);
        }

        private IrisObject loadPieceObject(String pieceKey, IrisJigsawPiece piece) {
            String objectKey = normalize(piece.getObject());
            pieceObjects.put(pieceKey, objectKey);
            if (objectKey.isEmpty()) {
                addDiagnostic(StructureGraphDiagnostic.Code.MISSING_OBJECT,
                        "Jigsaw piece '" + pieceKey + "' does not declare an object.",
                        "piece-object:empty:" + pieceKey);
                return null;
            }
            if (missingObjects.contains(objectKey)) {
                addDiagnostic(StructureGraphDiagnostic.Code.MISSING_OBJECT,
                        "Jigsaw piece '" + pieceKey + "' references missing object '" + objectKey + "'.",
                        "missing-object:" + objectKey + ":" + pieceKey);
                return null;
            }
            if (!attemptedObjects.add(objectKey)) {
                return graph.objects().get(objectKey);
            }

            IrisObject object = resolver.loadObject(objectKey);
            if (object == null) {
                missingObjects.add(objectKey);
                addDiagnostic(StructureGraphDiagnostic.Code.MISSING_OBJECT,
                        "Jigsaw piece '" + pieceKey + "' references missing object '" + objectKey + "'.",
                        "missing-object:" + objectKey + ":" + pieceKey);
                return null;
            }
            graph.objects().put(objectKey, object);
            if (object.getW() < 1 || object.getH() < 1 || object.getD() < 1) {
                addDiagnostic(StructureGraphDiagnostic.Code.INVALID_OBJECT_BOUNDS,
                        "Object '" + objectKey + "' used by jigsaw piece '" + pieceKey
                                + "' has invalid dimensions " + object.getW() + "x" + object.getH() + "x"
                                + object.getD() + ".",
                        "object-bounds:" + objectKey);
            }
            return object;
        }

        private void scanConnectors(String pieceKey, IrisJigsawPiece piece, IrisObject object) {
            List<ConnectorReference> references = new ArrayList<>();
            pieceConnectors.put(pieceKey, references);
            KList<IrisJigsawConnector> connectors = piece.getConnectors();
            if (connectors == null) {
                addDiagnostic(StructureGraphDiagnostic.Code.INVALID_CONNECTOR,
                        "Jigsaw piece '" + pieceKey + "' declares a null connector list.",
                        "connector-list:null:" + pieceKey);
                return;
            }
            for (int index = 0; index < connectors.size(); index++) {
                IrisJigsawConnector connector = connectors.get(index);
                if (connector == null) {
                    addDiagnostic(StructureGraphDiagnostic.Code.INVALID_CONNECTOR,
                            "Jigsaw piece '" + pieceKey + "' connectors[" + index + "] is null.",
                            "connector:null:" + pieceKey + ":" + index);
                    continue;
                }

                boolean validDirection = connector.getDirection() != null;
                if (!validDirection) {
                    addDiagnostic(StructureGraphDiagnostic.Code.INVALID_CONNECTOR_DIRECTION,
                            "Jigsaw piece '" + pieceKey + "' connectors[" + index
                                    + "] does not declare a direction.",
                            "connector:direction:" + pieceKey + ":" + index);
                }
                boolean validTop = connector.getTop() != null;
                if (!validTop) {
                    addDiagnostic(StructureGraphDiagnostic.Code.INVALID_CONNECTOR_DIRECTION,
                            "Jigsaw piece '" + pieceKey + "' connectors[" + index
                                    + "] does not declare a top direction.",
                            "connector:top:" + pieceKey + ":" + index);
                }
                boolean validJoint = connector.getJoint() != null;
                if (!validJoint) {
                    addDiagnostic(StructureGraphDiagnostic.Code.INVALID_CONNECTOR,
                            "Jigsaw piece '" + pieceKey + "' connectors[" + index
                                    + "] does not declare a joint type.",
                            "connector:joint:" + pieceKey + ":" + index);
                }
                boolean validPosition = isValidPosition(connector.getPosition(), object);
                if (connector.getPosition() == null || object != null && !validPosition) {
                    addDiagnostic(StructureGraphDiagnostic.Code.INVALID_CONNECTOR_POSITION,
                            invalidPositionMessage(pieceKey, index, connector.getPosition(), object),
                            "connector:position:" + pieceKey + ":" + index);
                }

                boolean validNames = connector.getName() != null && connector.getTargetName() != null;
                if (!validNames) {
                    addDiagnostic(StructureGraphDiagnostic.Code.INVALID_CONNECTOR,
                            "Jigsaw piece '" + pieceKey + "' connectors[" + index
                                    + "] must declare non-null name and targetName values.",
                            "connector:names:" + pieceKey + ":" + index);
                }

                String poolKey = normalize(connector.getPool());
                if (poolKey.isEmpty()) {
                    addDiagnostic(StructureGraphDiagnostic.Code.MISSING_CONNECTOR_POOL,
                            "Jigsaw piece '" + pieceKey + "' connectors[" + index
                                    + "] does not declare a target pool.",
                            "connector:pool-empty:" + pieceKey + ":" + index);
                } else {
                    requestPool(poolKey, new PoolReference(
                            "Jigsaw piece '" + pieceKey + "' connectors[" + index
                                    + "] references missing pool '" + poolKey + "'.",
                            StructureGraphDiagnostic.Code.MISSING_POOL));
                }
                references.add(new ConnectorReference(
                        pieceKey, index, connector, validPosition, validDirection, validTop, validJoint, validNames));
            }
        }

        private String invalidPositionMessage(String pieceKey, int index, IrisPosition position, IrisObject object) {
            if (position == null) {
                return "Jigsaw piece '" + pieceKey + "' connectors[" + index
                        + "] does not declare a position.";
            }
            if (object == null) {
                return "Jigsaw piece '" + pieceKey + "' connectors[" + index
                        + "] position " + position + " cannot be checked because its object is missing.";
            }
            return "Jigsaw piece '" + pieceKey + "' connectors[" + index + "] position " + position
                    + " is outside object bounds 0.." + (object.getW() - 1) + ", 0.." + (object.getH() - 1)
                    + ", 0.." + (object.getD() - 1) + ".";
        }

        private boolean isValidPosition(IrisPosition position, IrisObject object) {
            if (position == null || object == null) {
                return false;
            }
            return position.getX() >= 0 && position.getX() < object.getW()
                    && position.getY() >= 0 && position.getY() < object.getH()
                    && position.getZ() >= 0 && position.getZ() < object.getD();
        }

        private void detectFallbackCycles() {
            Map<String, Integer> states = new HashMap<>();
            for (String poolKey : graph.pools().keySet()) {
                detectFallbackCycle(poolKey, states);
            }
        }

        private void detectFallbackCycle(String startPool, Map<String, Integer> states) {
            if (states.getOrDefault(startPool, 0) == 2) {
                return;
            }
            List<String> path = new ArrayList<>();
            Map<String, Integer> pathIndexes = new HashMap<>();
            String poolKey = startPool;
            while (!poolKey.isEmpty() && graph.pools().containsKey(poolKey)
                    && states.getOrDefault(poolKey, 0) != 2) {
                Integer cycleStart = pathIndexes.get(poolKey);
                if (cycleStart != null) {
                    List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
                    cycle.add(poolKey);
                    String cyclePath = String.join(" -> ", cycle);
                    addDiagnostic(StructureGraphDiagnostic.Code.FALLBACK_CYCLE,
                            "Jigsaw pool fallback cycle detected: " + cyclePath + ".",
                            "fallback-cycle:" + cyclePath);
                    break;
                }
                pathIndexes.put(poolKey, path.size());
                path.add(poolKey);
                states.put(poolKey, 1);
                IrisJigsawPool pool = graph.pools().get(poolKey);
                poolKey = pool == null ? "" : JigsawPoolSelection.directFallbackKey(pool);
            }
            for (String visitedPool : path) {
                states.put(visitedPool, 2);
            }
        }

        private void analyzeReachability() {
            String startPool = normalize(structure.getStartPool());
            if (!graph.pools().containsKey(startPool)) {
                return;
            }

            graph.reachablePools().add(startPool);
            Deque<PieceReachState> pendingPieces = new ArrayDeque<>();
            for (PoolEntryReference entry : poolEntries.getOrDefault(startPool, List.of())) {
                markReachableEntry(entry);
                if (entry.empty()) {
                    continue;
                }
                IrisJigsawPiece piece = graph.pieces().get(entry.pieceKey());
                if (piece == null) {
                    continue;
                }
                for (int rotation : rotationsFor(piece)) {
                    markReachablePieceState(new PieceReachState(entry.pieceKey(), -1, rotation), pendingPieces);
                }
            }
            if (pendingPieces.isEmpty()) {
                addDiagnostic(StructureGraphDiagnostic.Code.NO_VIABLE_START_PIECE,
                        "Structure '" + structureKey() + "' has no start-pool entry with a positive weight,"
                                + " resolvable piece, and resolvable object.",
                        "structure:no-viable-start");
            }

            while (!pendingPieces.isEmpty()) {
                PieceReachState pieceState = pendingPieces.removeFirst();
                for (ConnectorReference source : pieceConnectors.getOrDefault(pieceState.pieceKey(), List.of())) {
                    if (source.index() == pieceState.skippedConnectorIndex() || !source.canSource()) {
                        continue;
                    }
                    OrientedConnector orientedSource = new OrientedConnector(source, pieceState.rotation());
                    activeConnectors.computeIfAbsent(source.id(), ignored -> new ArrayList<>())
                            .add(orientedSource);
                    List<String> candidatePools = candidatePools(normalize(source.connector().getPool()), true);
                    for (String candidatePool : candidatePools) {
                        graph.reachablePools().add(candidatePool);
                        for (PoolEntryReference entry : poolEntries.getOrDefault(candidatePool, List.of())) {
                            if (!entryIsViable(entry)) {
                                continue;
                            }
                            List<ConnectorMatch> compatible = findCompatibleConnectors(
                                    orientedSource, entry.pieceKey());
                            if (!compatible.isEmpty()) {
                                markReachableEntry(entry);
                                for (ConnectorMatch target : compatible) {
                                    markReachablePieceState(
                                            new PieceReachState(entry.pieceKey(), target.connector().index(),
                                                    target.rotation()),
                                            pendingPieces);
                                }
                            }
                        }
                    }
                }
            }

            reportUnmatchedReachableConnectors();
            reportUnreachableResources();
        }

        private void markReachableEntry(PoolEntryReference entry) {
            if (!entryIsViable(entry)) {
                return;
            }
            reachableEntries.add(entry.id());
            if (!entry.empty()) {
                graph.reachablePieces().add(entry.pieceKey());
            }
        }

        private void markReachablePieceState(PieceReachState state, Deque<PieceReachState> pendingPieces) {
            if (!graph.reachablePieces().contains(state.pieceKey())) {
                return;
            }
            if (reachablePieceStates.add(state)) {
                pendingPieces.addLast(state);
            }
        }

        private boolean entryIsViable(PoolEntryReference entry) {
            if (entry.weight() <= 0) {
                return false;
            }
            if (entry.empty()) {
                return entry.pieceKey().isEmpty();
            }
            if (entry.pieceKey().isEmpty() || !graph.pieces().containsKey(entry.pieceKey())) {
                return false;
            }
            String objectKey = pieceObjects.getOrDefault(entry.pieceKey(), "");
            return !objectKey.isEmpty() && graph.objects().containsKey(objectKey);
        }

        private void reportUnmatchedReachableConnectors() {
            for (List<OrientedConnector> orientations : activeConnectors.values()) {
                OrientedConnector representative = orientations.getFirst();
                List<String> candidates = candidatePools(
                        normalize(representative.definition().getPool()), true);
                boolean hasViableEntry = false;
                boolean hasCompatibleEntry = false;
                for (String candidatePool : candidates) {
                    for (PoolEntryReference entry : poolEntries.getOrDefault(candidatePool, List.of())) {
                        if (!entryIsViable(entry)) {
                            continue;
                        }
                        hasViableEntry = true;
                        if (entry.empty()) {
                            hasCompatibleEntry = true;
                            break;
                        }
                        for (OrientedConnector source : orientations) {
                            if (!findCompatibleConnectors(source, entry.pieceKey()).isEmpty()) {
                                hasCompatibleEntry = true;
                                break;
                            }
                        }
                        if (hasCompatibleEntry) {
                            break;
                        }
                    }
                    if (hasCompatibleEntry) {
                        break;
                    }
                }
                if (hasViableEntry && !hasCompatibleEntry) {
                    addDiagnostic(StructureGraphDiagnostic.Code.NO_COMPATIBLE_CONNECTOR,
                            "Jigsaw piece '" + representative.connector().pieceKey() + "' connectors["
                                    + representative.connector().index() + "] targets pool '"
                                    + normalize(representative.definition().getPool())
                                    + "', but no reachable candidate exposes the requested target name with a"
                                    + " compatible direction.",
                            "connector:no-match:" + representative.connector().pieceKey() + ":"
                                    + representative.connector().index());
                }
            }
        }

        private void reportUnreachableResources() {
            for (String poolKey : graph.pools().keySet()) {
                if (!graph.reachablePools().contains(poolKey)) {
                    addDiagnostic(StructureGraphDiagnostic.Code.UNREACHABLE_POOL,
                            "Jigsaw pool '" + poolKey + "' is in the structure closure but cannot be reached"
                                    + " from the start pool.",
                            "unreachable-pool:" + poolKey);
                    continue;
                }
                for (PoolEntryReference entry : poolEntries.getOrDefault(poolKey, List.of())) {
                    if (!entry.empty() && entryIsViable(entry) && !reachableEntries.contains(entry.id())) {
                        addDiagnostic(StructureGraphDiagnostic.Code.UNREACHABLE_PIECE,
                                "Jigsaw pool '" + poolKey + "' pieces[" + entry.index() + "] references piece '"
                                        + entry.pieceKey() + "', but no reachable connector can attach it.",
                                "unreachable-piece:" + entry.id());
                    }
                }
            }
        }

        private List<ConnectorMatch> findCompatibleConnectors(OrientedConnector source,
                                                              String candidatePieceKey) {
            List<ConnectorMatch> compatible = new ArrayList<>();
            IrisJigsawPiece candidatePiece = graph.pieces().get(candidatePieceKey);
            if (candidatePiece == null) {
                return compatible;
            }
            for (ConnectorReference candidate : pieceConnectors.getOrDefault(candidatePieceKey, List.of())) {
                for (int rotation : rotationsFor(candidatePiece)) {
                    if (connectorsCompatible(source, candidate, rotation)) {
                        compatible.add(new ConnectorMatch(candidate, rotation));
                    }
                }
            }
            return compatible;
        }

        private boolean connectorsCompatible(OrientedConnector source, ConnectorReference candidate,
                                             int candidateRotation) {
            if (!source.connector().canSource() || !candidate.canTarget()) {
                return false;
            }
            IrisJigsawConnector sourceConnector = source.definition();
            IrisJigsawConnector candidateConnector = candidate.connector();
            if (!normalize(sourceConnector.getTargetName()).equals(normalize(candidateConnector.getName()))) {
                return false;
            }

            IrisDirection sourceDirection = rotateDirection(
                    sourceConnector.getDirection(), source.rotation());
            IrisDirection candidateDirection = rotateDirection(
                    candidateConnector.getDirection(), candidateRotation);
            if (candidateDirection != sourceDirection.reverse()) {
                return false;
            }
            if (sourceConnector.getJoint() != JigsawJoint.ALIGNED) {
                return true;
            }
            IrisDirection sourceTop = rotateDirection(sourceConnector.getTop(), source.rotation());
            IrisDirection candidateTop = rotateDirection(candidateConnector.getTop(), candidateRotation);
            return candidateTop == sourceTop;
        }

        private List<String> candidatePools(String firstPool, boolean includeFirst) {
            List<String> result = new ArrayList<>();
            IrisJigsawPool pool = graph.pools().get(firstPool);
            if (pool == null) {
                return result;
            }
            for (String candidate : JigsawPoolSelection.candidatePoolKeys(firstPool, pool, includeFirst)) {
                if (graph.pools().containsKey(candidate)) {
                    result.add(candidate);
                }
            }
            return result;
        }

        private List<StructureGraphAssemblySample> sampleAssemblies() {
            List<StructureGraphAssemblySample> samples = new ArrayList<>(ASSEMBLY_SEEDS.size());
            for (long seed : ASSEMBLY_SEEDS) {
                samples.add(sampleAssembly(seed));
            }
            return samples;
        }

        private StructureGraphAssemblySample sampleAssembly(long seed) {
            SplittableRandom random = new SplittableRandom(seed);
            List<String> pieces = new ArrayList<>();
            Deque<SampleConnector> open = new ArrayDeque<>();
            String startPool = normalize(structure.getStartPool());
            PoolEntryReference start = weightedPick(viableEntries(startPool), random);
            if (start == null) {
                return new StructureGraphAssemblySample(seed,
                        new StructureGraphAssemblySample.Outcome(pieces, 1, false));
            }
            if (start.empty()) {
                return new StructureGraphAssemblySample(seed,
                        new StructureGraphAssemblySample.Outcome(pieces, 0, false, true));
            }

            IrisJigsawPiece startPiece = graph.pieces().get(start.pieceKey());
            int startRotation = startPiece != null && startPiece.isRotatable()
                    ? Y_ROTATIONS[random.nextInt(Y_ROTATIONS.length)]
                    : 0;
            addSamplePiece(start.pieceKey(), -1, 0, startRotation, pieces, open);
            int unresolvedConnectors = 0;
            while (!open.isEmpty() && pieces.size() < ASSEMBLY_PIECE_CAP) {
                SampleConnector source = open.removeFirst();
                AttachmentCandidate candidate = sampleCandidate(source, random);
                if (candidate == null) {
                    if (!isIntentionalTermination(source)) {
                        unresolvedConnectors++;
                    }
                    continue;
                }
                if (candidate.entry().empty()) {
                    continue;
                }
                if (source.depth() < Math.max(1, structure.getMaxDepth())) {
                    addSamplePiece(candidate.entry().pieceKey(), candidate.match().connector().index(),
                            source.depth() + 1, candidate.match().rotation(), pieces, open);
                } else {
                    pieces.add(candidate.entry().pieceKey());
                }
            }
            boolean capped = !open.isEmpty();
            return new StructureGraphAssemblySample(seed,
                    new StructureGraphAssemblySample.Outcome(pieces, unresolvedConnectors, capped));
        }

        private AttachmentCandidate sampleCandidate(SampleConnector source, SplittableRandom random) {
            String primaryPool = normalize(source.connector().definition().getPool());
            boolean withinDepth = source.depth() < Math.max(1, structure.getMaxDepth());
            List<String> candidates = candidatePools(primaryPool, withinDepth);
            for (String candidatePool : candidates) {
                List<AttachmentCandidate> attachments = new ArrayList<>();
                for (PoolEntryReference entry : viableEntries(candidatePool)) {
                    if (entry.empty()) {
                        attachments.add(new AttachmentCandidate(entry, null));
                        continue;
                    }
                    List<ConnectorMatch> matching = findCompatibleConnectors(
                            source.connector(), entry.pieceKey());
                    if (!matching.isEmpty()) {
                        attachments.add(new AttachmentCandidate(entry, matching.getFirst()));
                    }
                }
                AttachmentCandidate selected = weightedPickAttachments(attachments, random);
                if (selected != null) {
                    return selected;
                }
            }
            return null;
        }

        private boolean isIntentionalTermination(SampleConnector source) {
            String poolKey = normalize(source.connector().definition().getPool());
            IrisJigsawPool pool = graph.pools().get(poolKey);
            if (pool == null) {
                return false;
            }
            boolean includePrimary = source.depth() < Math.max(1, structure.getMaxDepth());
            for (String candidate : candidatePools(poolKey, includePrimary)) {
                IrisJigsawPool candidatePool = graph.pools().get(candidate);
                if (candidatePool != null && candidatePool.getPieces() != null
                        && candidatePool.getPieces().isEmpty()) {
                    return true;
                }
            }
            return !includePrimary;
        }

        private void addSamplePiece(String pieceKey, int skippedConnector, int connectorDepth, int rotation,
                                    List<String> pieces, Deque<SampleConnector> open) {
            pieces.add(pieceKey);
            for (ConnectorReference connector : pieceConnectors.getOrDefault(pieceKey, List.of())) {
                if (connector.index() != skippedConnector && connector.canSource()) {
                    open.addLast(new SampleConnector(
                            new OrientedConnector(connector, rotation), connectorDepth));
                }
            }
        }

        private List<PoolEntryReference> viableEntries(String poolKey) {
            List<PoolEntryReference> result = new ArrayList<>();
            for (PoolEntryReference entry : poolEntries.getOrDefault(poolKey, List.of())) {
                if (entryIsViable(entry)) {
                    result.add(entry);
                }
            }
            return result;
        }

        private PoolEntryReference weightedPick(List<PoolEntryReference> entries, SplittableRandom random) {
            long totalWeight = 0L;
            for (PoolEntryReference entry : entries) {
                totalWeight += entry.weight();
            }
            if (totalWeight <= 0L) {
                return null;
            }
            long target = random.nextLong(totalWeight);
            for (PoolEntryReference entry : entries) {
                target -= entry.weight();
                if (target < 0L) {
                    return entry;
                }
            }
            return entries.getLast();
        }

        private AttachmentCandidate weightedPickAttachments(List<AttachmentCandidate> attachments,
                                                            SplittableRandom random) {
            long totalWeight = 0L;
            for (AttachmentCandidate attachment : attachments) {
                totalWeight += attachment.entry().weight();
            }
            if (totalWeight <= 0L) {
                return null;
            }
            long target = random.nextLong(totalWeight);
            for (AttachmentCandidate attachment : attachments) {
                target -= attachment.entry().weight();
                if (target < 0L) {
                    return attachment;
                }
            }
            return attachments.getLast();
        }

        private void reportPieceCapSamples(List<StructureGraphAssemblySample> samples) {
            List<String> cappedSeeds = new ArrayList<>();
            for (StructureGraphAssemblySample sample : samples) {
                if (sample.outcome().pieceCapReached()) {
                    cappedSeeds.add(Long.toString(sample.seed()));
                }
            }
            if (!cappedSeeds.isEmpty()) {
                addDiagnostic(StructureGraphDiagnostic.Code.ASSEMBLY_PIECE_CAP_REACHED,
                        "Deterministic structural assembly reached the " + ASSEMBLY_PIECE_CAP
                                + "-piece safety cap for seeds " + String.join(", ", cappedSeeds) + ".",
                        "assembly:piece-cap");
            }
        }

        private CompiledStructureGraph buildGraph() {
            return graph.build();
        }

        private List<StructureGraphDiagnostic> diagnostics() {
            return diagnostics;
        }

        private void addDiagnostic(StructureGraphDiagnostic.Code code, String message, String deduplicationKey) {
            if (diagnosticKeys.add(code.name() + ":" + deduplicationKey)) {
                diagnostics.add(new StructureGraphDiagnostic(code, message));
            }
        }

        private String structureKey() {
            String key = structure.getLoadKey();
            return key == null || key.isBlank() ? "<unloaded>" : key;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static int[] rotationsFor(IrisJigsawPiece piece) {
        return piece.isRotatable() ? Y_ROTATIONS : NO_ROTATION;
    }

    private static IrisDirection rotateDirection(IrisDirection direction, int degrees) {
        if (direction.isVertical()) {
            return direction;
        }
        int turns = Math.floorMod(degrees, 360) / 90;
        IrisDirection rotated = direction;
        for (int turn = 0; turn < turns; turn++) {
            rotated = switch (rotated) {
                case NORTH_NEGATIVE_Z -> IrisDirection.WEST_NEGATIVE_X;
                case WEST_NEGATIVE_X -> IrisDirection.SOUTH_POSITIVE_Z;
                case SOUTH_POSITIVE_Z -> IrisDirection.EAST_POSITIVE_X;
                case EAST_POSITIVE_X -> IrisDirection.NORTH_NEGATIVE_Z;
                case UP_POSITIVE_Y, DOWN_NEGATIVE_Y -> rotated;
            };
        }
        return rotated;
    }

    private record PoolReference(String message, StructureGraphDiagnostic.Code code) {
    }

    private record PoolEntryReference(String poolKey, int index, String pieceKey, int weight, boolean empty) {
        private String id() {
            return poolKey + "#" + index;
        }
    }

    private record ConnectorReference(String pieceKey, int index, IrisJigsawConnector connector,
                                      boolean validPosition, boolean validDirection, boolean validTop,
                                      boolean validJoint, boolean validNames) {
        private String id() {
            return pieceKey + "#" + index;
        }

        private boolean canSource() {
            return canTarget() && !normalize(connector.getPool()).isEmpty();
        }

        private boolean canTarget() {
            return connector != null && validPosition && validDirection && validTop && validJoint && validNames;
        }
    }

    private record PieceReachState(String pieceKey, int skippedConnectorIndex, int rotation) {
    }

    private record OrientedConnector(ConnectorReference connector, int rotation) {
        private IrisJigsawConnector definition() {
            return connector.connector();
        }
    }

    private record ConnectorMatch(ConnectorReference connector, int rotation) {
    }

    private record SampleConnector(OrientedConnector connector, int depth) {
    }

    private record AttachmentCandidate(PoolEntryReference entry, ConnectorMatch match) {
    }
}
