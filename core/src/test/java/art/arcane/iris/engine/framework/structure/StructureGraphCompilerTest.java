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
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.volmlib.util.collection.KList;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StructureGraphCompilerTest {
    @BeforeClass
    public static void bindPlatform() {
        IrisPlatforms.unbind();
        PlatformBlockState block = mock(PlatformBlockState.class);
        PlatformRegistries registries = mock(PlatformRegistries.class);
        when(registries.block(anyString())).thenReturn(block);
        IrisPlatform platform = mock(IrisPlatform.class);
        when(platform.registries()).thenReturn(registries);
        IrisPlatforms.bind(platform);
    }

    @AfterClass
    public static void unbindPlatform() {
        IrisPlatforms.unbind();
    }

    @Test
    public void compilesACompleteTerminalGraphAndSamplesDeterministically() {
        InMemoryResolver resolver = new InMemoryResolver();
        resolver.objects.put("objects/start", object());
        resolver.pieces.put("pieces/start", piece("objects/start"));
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));
        IrisStructure structure = structure("pools/start");

        StructureGraphCompilation first = StructureGraphCompiler.compile(structure, resolver);
        StructureGraphCompilation second = StructureGraphCompiler.compile(structure, resolver);

        assertTrue(first.getDiagnostics().isEmpty());
        assertTrue(first.isAssemblyViable());
        assertEquals(List.of("pools/start"), new ArrayList<>(first.getGraph().getPools().keySet()));
        assertEquals(List.of("pieces/start"), new ArrayList<>(first.getGraph().getPieces().keySet()));
        assertEquals(List.of("objects/start"), new ArrayList<>(first.getGraph().getObjects().keySet()));
        assertEquals(first.getAssemblySamples(), second.getAssemblySamples());
        for (StructureGraphAssemblySample sample : first.getAssemblySamples()) {
            assertEquals(List.of("pieces/start"), sample.outcome().pieceKeys());
            assertTrue(sample.outcome().isComplete());
        }
    }

    @Test
    public void reportsMissingReferencesInvalidWeightsAndNoViableStart() {
        InMemoryResolver resolver = new InMemoryResolver();
        IrisJigsawPool start = pool(entry("pieces/missing", 0));
        start.setFallback("pools/missing-fallback");
        resolver.pools.put("pools/start", start);

        StructureGraphCompilation result = StructureGraphCompiler.compile(structure("pools/start"), resolver);

        assertCodes(result,
                StructureGraphDiagnostic.Code.INVALID_WEIGHT,
                StructureGraphDiagnostic.Code.MISSING_PIECE,
                StructureGraphDiagnostic.Code.MISSING_POOL,
                StructureGraphDiagnostic.Code.NO_VIABLE_START_PIECE);
        assertTrue(result.hasErrors());
        assertFalse(result.isAssemblyViable());
    }

    @Test
    public void reportsEmptyPoolsAndConnectorFieldFailures() {
        InMemoryResolver resolver = new InMemoryResolver();
        IrisJigsawConnector connector = connector(new IrisPosition(4, 1, 1), null, "", "path", "path");
        resolver.objects.put("objects/start", object());
        resolver.pieces.put("pieces/start", piece("objects/start", connector));
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));
        resolver.pools.put("pools/empty", pool());

        StructureGraphCompilation invalidConnector = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);
        StructureGraphCompilation emptyStart = StructureGraphCompiler.compile(
                structure("pools/empty"), resolver);

        assertCodes(invalidConnector,
                StructureGraphDiagnostic.Code.INVALID_CONNECTOR_POSITION,
                StructureGraphDiagnostic.Code.INVALID_CONNECTOR_DIRECTION,
                StructureGraphDiagnostic.Code.MISSING_CONNECTOR_POOL);
        assertCodes(emptyStart,
                StructureGraphDiagnostic.Code.EMPTY_START_POOL,
                StructureGraphDiagnostic.Code.NO_VIABLE_START_PIECE);
    }

    @Test
    public void reportsIncompatibleDirectionsAndUnreachablePieces() {
        InMemoryResolver resolver = connectorGraph(false, IrisDirection.NORTH_NEGATIVE_Z);

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertCodes(result,
                StructureGraphDiagnostic.Code.NO_COMPATIBLE_CONNECTOR,
                StructureGraphDiagnostic.Code.UNREACHABLE_PIECE);
        assertFalse(result.getGraph().getReachablePieces().contains("pieces/target"));
        assertFalse(result.isAssemblyViable());
        for (StructureGraphAssemblySample sample : result.getAssemblySamples()) {
            assertFalse(sample.outcome().isComplete());
        }
    }

    @Test
    public void acceptsHorizontalRotationWithVanillaOneWayTargetMatching() {
        InMemoryResolver resolver = connectorGraph(true, IrisDirection.NORTH_NEGATIVE_Z);

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertTrue(result.getDiagnostics().isEmpty());
        assertTrue(result.getGraph().getReachablePieces().contains("pieces/target"));
        for (StructureGraphAssemblySample sample : result.getAssemblySamples()) {
            assertEquals(List.of("pieces/start", "pieces/target"), sample.outcome().pieceKeys());
            assertTrue(sample.outcome().isComplete());
        }
    }

    @Test
    public void rejectsVerticalConnectorsThatYRotationCannotAlign() {
        InMemoryResolver resolver = connectorGraph(true, IrisDirection.UP_POSITIVE_Y);

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertCodes(result,
                StructureGraphDiagnostic.Code.NO_COMPATIBLE_CONNECTOR,
                StructureGraphDiagnostic.Code.UNREACHABLE_PIECE);
    }

    @Test
    public void alignedConnectorsRequireMatchingTopOrientation() {
        InMemoryResolver resolver = connectorGraph(false, IrisDirection.SOUTH_POSITIVE_Z);
        IrisJigsawConnector source = resolver.pieces.get("pieces/start").getConnectors().getFirst();
        IrisJigsawConnector target = resolver.pieces.get("pieces/target").getConnectors().getFirst();
        source.setJoint(JigsawJoint.ALIGNED);
        source.setTop(IrisDirection.UP_POSITIVE_Y);
        target.setTop(IrisDirection.DOWN_NEGATIVE_Y);

        StructureGraphCompilation aligned = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);
        assertCodes(aligned,
                StructureGraphDiagnostic.Code.NO_COMPATIBLE_CONNECTOR,
                StructureGraphDiagnostic.Code.UNREACHABLE_PIECE);

        source.setJoint(JigsawJoint.ROLLABLE);
        StructureGraphCompilation rollable = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);
        assertTrue(rollable.isAssemblyViable());
    }

    @Test
    public void deterministicSamplingUsesFallbackAtTheMaximumDepth() {
        InMemoryResolver resolver = new InMemoryResolver();
        IrisJigsawConnector source = connector(
                new IrisPosition(1, 1, 0), IrisDirection.NORTH_NEGATIVE_Z,
                "pools/primary", "source", "door");
        IrisJigsawConnector primaryTarget = connector(
                new IrisPosition(1, 1, 2), IrisDirection.SOUTH_POSITIVE_Z,
                "pools/primary", "door", "unused");
        IrisJigsawConnector primarySource = connector(
                new IrisPosition(1, 1, 0), IrisDirection.NORTH_NEGATIVE_Z,
                "pools/primary", "source", "door");
        IrisJigsawConnector fallbackTarget = connector(
                new IrisPosition(1, 1, 2), IrisDirection.SOUTH_POSITIVE_Z,
                "pools/primary", "door", "unused");
        resolver.objects.put("objects/start", object());
        resolver.objects.put("objects/primary", object());
        resolver.objects.put("objects/fallback", object());
        resolver.pieces.put("pieces/start", piece("objects/start", source));
        resolver.pieces.put("pieces/primary", piece("objects/primary", primaryTarget, primarySource));
        resolver.pieces.put("pieces/fallback", piece("objects/fallback", fallbackTarget));
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));
        IrisJigsawPool primary = pool(entry("pieces/primary", 1));
        primary.setFallback("pools/fallback");
        resolver.pools.put("pools/primary", primary);
        resolver.pools.put("pools/fallback", pool(entry("pieces/fallback", 1)));
        IrisStructure structure = structure("pools/start");
        structure.setMaxDepth(1);

        StructureGraphCompilation result = StructureGraphCompiler.compile(structure, resolver);

        assertTrue(result.getDiagnostics().isEmpty());
        assertTrue(result.isAssemblyViable());
        for (StructureGraphAssemblySample sample : result.getAssemblySamples()) {
            assertEquals(List.of("pieces/start", "pieces/primary", "pieces/fallback"),
                    sample.outcome().pieceKeys());
            assertTrue(sample.outcome().isComplete());
        }
    }

    @Test
    public void deterministicSamplingDoesNotSelectFallbackOfFallback() {
        InMemoryResolver resolver = new InMemoryResolver();
        IrisJigsawConnector source = connector(
                new IrisPosition(1, 1, 0), IrisDirection.NORTH_NEGATIVE_Z,
                "pools/primary", "source", "door");
        IrisJigsawConnector incompatible = connector(
                new IrisPosition(1, 1, 2), IrisDirection.SOUTH_POSITIVE_Z,
                "pools/primary", "wrong", "unused");
        IrisJigsawConnector nestedTarget = connector(
                new IrisPosition(1, 1, 2), IrisDirection.SOUTH_POSITIVE_Z,
                "pools/primary", "door", "unused");
        resolver.objects.put("objects/start", object());
        resolver.objects.put("objects/incompatible", object());
        resolver.objects.put("objects/nested", object());
        resolver.pieces.put("pieces/start", piece("objects/start", source));
        resolver.pieces.put("pieces/incompatible", piece("objects/incompatible", incompatible));
        resolver.pieces.put("pieces/nested", piece("objects/nested", nestedTarget));
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));
        IrisJigsawPool primary = pool(entry("pieces/incompatible", 1));
        primary.setFallback("pools/direct");
        resolver.pools.put("pools/primary", primary);
        IrisJigsawPool direct = pool(entry("pieces/incompatible", 1));
        direct.setFallback("pools/nested");
        resolver.pools.put("pools/direct", direct);
        resolver.pools.put("pools/nested", pool(entry("pieces/nested", 1)));

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertFalse(result.getGraph().getReachablePools().contains("pools/nested"));
        assertFalse(result.getGraph().getReachablePieces().contains("pieces/nested"));
        assertFalse(result.isAssemblyViable());
        for (StructureGraphAssemblySample sample : result.getAssemblySamples()) {
            assertEquals(List.of("pieces/start"), sample.outcome().pieceKeys());
            assertEquals(1, sample.outcome().unresolvedConnectorCount());
        }
    }

    @Test
    public void reportsFallbackCyclesAndTheirUnreachableClosure() {
        InMemoryResolver resolver = new InMemoryResolver();
        resolver.objects.put("objects/terminal", object());
        resolver.pieces.put("pieces/terminal", piece("objects/terminal"));
        IrisJigsawPool start = pool(entry("pieces/terminal", 1));
        IrisJigsawPool first = pool(entry("pieces/terminal", 1));
        IrisJigsawPool second = pool(entry("pieces/terminal", 1));
        start.setFallback("pools/first");
        first.setFallback("pools/second");
        second.setFallback("pools/first");
        resolver.pools.put("pools/start", start);
        resolver.pools.put("pools/first", first);
        resolver.pools.put("pools/second", second);

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertCodes(result,
                StructureGraphDiagnostic.Code.FALLBACK_CYCLE,
                StructureGraphDiagnostic.Code.UNREACHABLE_POOL);
        assertTrue(result.hasErrors());
    }

    @Test
    public void validatesLongFallbackChainsWithoutRecursiveTraversal() {
        InMemoryResolver resolver = new InMemoryResolver();
        resolver.objects.put("objects/terminal", object());
        resolver.pieces.put("pieces/terminal", piece("objects/terminal"));
        int poolCount = 10_000;
        for (int index = 0; index < poolCount; index++) {
            IrisJigsawPool pool = pool(entry("pieces/terminal", 1));
            if (index + 1 < poolCount) {
                pool.setFallback("pools/chain-" + (index + 1));
            }
            resolver.pools.put("pools/chain-" + index, pool);
        }

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/chain-0"), resolver);

        assertTrue(result.getDiagnostics().stream().noneMatch(
                diagnostic -> diagnostic.code() == StructureGraphDiagnostic.Code.FALLBACK_CYCLE));
        assertTrue(result.isAssemblyViable());
    }

    @Test
    public void reportsMissingObjectsAndInvalidObjectBounds() {
        InMemoryResolver resolver = new InMemoryResolver();
        resolver.objects.put("objects/empty", new IrisObject());
        resolver.pieces.put("pieces/missing-object", piece("objects/missing"));
        resolver.pieces.put("pieces/empty-object", piece("objects/empty"));
        resolver.pools.put("pools/start", pool(
                entry("pieces/missing-object", 1),
                entry("pieces/empty-object", 1)));

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertCodes(result,
                StructureGraphDiagnostic.Code.MISSING_OBJECT,
                StructureGraphDiagnostic.Code.INVALID_OBJECT_BOUNDS);
        assertTrue(result.hasErrors());
    }

    @Test
    public void rejectsNullConnectorCollectionsAndNames() {
        InMemoryResolver nullListResolver = new InMemoryResolver();
        nullListResolver.objects.put("objects/start", object());
        IrisJigsawPiece nullListPiece = piece("objects/start");
        nullListPiece.setConnectors(null);
        nullListResolver.pieces.put("pieces/start", nullListPiece);
        nullListResolver.pools.put("pools/start", pool(entry("pieces/start", 1)));

        StructureGraphCompilation nullList = StructureGraphCompiler.compile(
                structure("pools/start"), nullListResolver);

        assertCodes(nullList, StructureGraphDiagnostic.Code.INVALID_CONNECTOR);
        assertTrue(nullList.hasErrors());

        InMemoryResolver nullNamesResolver = connectorGraph(true, IrisDirection.SOUTH_POSITIVE_Z);
        nullNamesResolver.pieces.get("pieces/start").getConnectors().getFirst().setTargetName(null);
        StructureGraphCompilation nullNames = StructureGraphCompiler.compile(
                structure("pools/start"), nullNamesResolver);

        assertCodes(nullNames, StructureGraphDiagnostic.Code.INVALID_CONNECTOR);
        assertTrue(nullNames.hasErrors());
    }

    @Test
    public void rejectsStructureLimitsOutsideTheRuntimeContract() {
        InMemoryResolver resolver = new InMemoryResolver();
        resolver.objects.put("objects/start", object());
        resolver.pieces.put("pieces/start", piece("objects/start"));
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));

        IrisStructure deep = structure("pools/start");
        deep.setMaxDepth(31);
        IrisStructure wide = structure("pools/start");
        wide.setMaxSizeChunks(33);
        IrisStructure overflowing = structure("pools/start");
        overflowing.setMaxSizeChunks(Integer.MAX_VALUE);

        assertCodes(StructureGraphCompiler.compile(deep, resolver),
                StructureGraphDiagnostic.Code.INVALID_MAX_DEPTH);
        assertCodes(StructureGraphCompiler.compile(wide, resolver),
                StructureGraphDiagnostic.Code.INVALID_MAX_SIZE);
        assertCodes(StructureGraphCompiler.compile(overflowing, resolver),
                StructureGraphDiagnostic.Code.INVALID_MAX_SIZE);
    }

    @Test
    public void emptyPoolChoiceTerminatesABranchWithoutAPlaceholderPiece() {
        InMemoryResolver resolver = new InMemoryResolver();
        IrisJigsawConnector source = connector(
                new IrisPosition(1, 1, 0), IrisDirection.NORTH_NEGATIVE_Z,
                "pools/terminal", "source", "door");
        resolver.objects.put("objects/start", object());
        resolver.pieces.put("pieces/start", piece("objects/start", source));
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));
        resolver.pools.put("pools/terminal", pool(emptyEntry(4)));

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertTrue(result.getDiagnostics().isEmpty());
        assertTrue(result.isAssemblyViable());
        for (StructureGraphAssemblySample sample : result.getAssemblySamples()) {
            assertEquals(List.of("pieces/start"), sample.outcome().pieceKeys());
            assertTrue(sample.outcome().isComplete());
        }
    }

    @Test
    public void rareEmptyStartPreventsGuaranteedReplacementOutputWithoutDependingOnSamples() {
        InMemoryResolver resolver = new InMemoryResolver();
        resolver.objects.put("objects/start", object());
        resolver.pieces.put("pieces/start", piece("objects/start"));
        resolver.pools.put("pools/start", pool(
                entry("pieces/start", Integer.MAX_VALUE), emptyEntry(1)));

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertTrue(result.isAssemblyViable());
        assertFalse(result.guaranteesAssemblyOutput());
        assertTrue(result.getAssemblySamples().stream().noneMatch(
                sample -> sample.outcome().intentionalEmpty()));
    }

    @Test
    public void oversizedStartPreventsGuaranteedReplacementOutput() {
        InMemoryResolver resolver = new InMemoryResolver();
        resolver.objects.put("objects/start", new IrisObject(130, 3, 3));
        resolver.pieces.put("pieces/start", piece("objects/start"));
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));

        StructureGraphCompilation result = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertTrue(result.isAssemblyViable());
        assertFalse(result.guaranteesAssemblyOutput());
    }

    @Test
    public void reachableBranchRequiresAuthoredEmptyTerminationForReplacementOutput() {
        InMemoryResolver resolver = connectorGraph(true, IrisDirection.NORTH_NEGATIVE_Z);

        StructureGraphCompilation unbounded = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertTrue(unbounded.isAssemblyViable());
        assertFalse(unbounded.guaranteesAssemblyOutput());

        resolver.pools.get("pools/target").setFallback("pools/empty");
        resolver.pools.put("pools/empty", pool());
        StructureGraphCompilation terminated = StructureGraphCompiler.compile(
                structure("pools/start"), resolver);

        assertTrue(terminated.isAssemblyViable());
        assertTrue(terminated.guaranteesAssemblyOutput());
    }

    @Test
    public void defersTopologyPieceCapsToGeometryValidation() {
        InMemoryResolver resolver = new InMemoryResolver();
        IrisJigsawConnector startSource = connector(
                new IrisPosition(1, 1, 0), IrisDirection.NORTH_NEGATIVE_Z,
                "pools/recursive", "source", "door");
        IrisJigsawConnector recursiveTarget = connector(
                new IrisPosition(1, 1, 2), IrisDirection.SOUTH_POSITIVE_Z,
                "pools/recursive", "door", "unused");
        IrisJigsawConnector firstBranch = connector(
                new IrisPosition(1, 1, 0), IrisDirection.NORTH_NEGATIVE_Z,
                "pools/recursive", "source", "door");
        IrisJigsawConnector secondBranch = connector(
                new IrisPosition(1, 1, 0), IrisDirection.NORTH_NEGATIVE_Z,
                "pools/recursive", "source", "door");
        resolver.objects.put("objects/start", object());
        resolver.objects.put("objects/recursive", object());
        resolver.pieces.put("pieces/start", piece("objects/start", startSource));
        resolver.pieces.put("pieces/recursive", piece(
                "objects/recursive", recursiveTarget, firstBranch, secondBranch));
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));
        IrisJigsawPool recursive = pool(entry("pieces/recursive", 1));
        recursive.setFallback("pools/empty");
        resolver.pools.put("pools/recursive", recursive);
        resolver.pools.put("pools/empty", pool());
        IrisStructure structure = structure("pools/start");
        structure.setMaxDepth(30);

        StructureGraphCompilation result = StructureGraphCompiler.compile(structure, resolver);

        assertCodes(result, StructureGraphDiagnostic.Code.ASSEMBLY_PIECE_CAP_REACHED);
        assertTrue(result.isAssemblyViable());
        for (StructureGraphAssemblySample sample : result.getAssemblySamples()) {
            assertTrue(sample.outcome().pieceCapReached());
            assertEquals(0, sample.outcome().unresolvedConnectorCount());
        }
    }

    private static InMemoryResolver connectorGraph(boolean rotatable, IrisDirection targetDirection) {
        InMemoryResolver resolver = new InMemoryResolver();
        IrisJigsawConnector source = connector(
                new IrisPosition(1, 1, 0), IrisDirection.NORTH_NEGATIVE_Z,
                "pools/target", "source", "door");
        IrisJigsawConnector target = connector(
                new IrisPosition(1, 1, 0), targetDirection,
                "pools/target", "door", "unrelated-target");
        IrisJigsawPiece targetPiece = piece("objects/target", target);
        targetPiece.setRotatable(rotatable);
        resolver.objects.put("objects/start", object());
        resolver.objects.put("objects/target", object());
        IrisJigsawPiece startPiece = piece("objects/start", source);
        startPiece.setRotatable(false);
        resolver.pieces.put("pieces/start", startPiece);
        resolver.pieces.put("pieces/target", targetPiece);
        resolver.pools.put("pools/start", pool(entry("pieces/start", 1)));
        resolver.pools.put("pools/target", pool(entry("pieces/target", 1)));
        return resolver;
    }

    private static IrisStructure structure(String startPool) {
        IrisStructure structure = new IrisStructure();
        structure.setLoadKey("structures/test");
        structure.setStartPool(startPool);
        structure.setMaxDepth(3);
        structure.setMaxSizeChunks(4);
        return structure;
    }

    private static IrisObject object() {
        return new IrisObject(3, 3, 3);
    }

    private static IrisJigsawPiece piece(String object, IrisJigsawConnector... connectors) {
        IrisJigsawPiece piece = new IrisJigsawPiece();
        piece.setObject(object);
        piece.setConnectors(new KList<>(connectors));
        return piece;
    }

    private static IrisJigsawPool pool(IrisJigsawPieceEntry... entries) {
        IrisJigsawPool pool = new IrisJigsawPool();
        pool.setPieces(new KList<>(entries));
        return pool;
    }

    private static IrisJigsawPieceEntry entry(String piece, int weight) {
        return new IrisJigsawPieceEntry().setPiece(piece).setWeight(weight);
    }

    private static IrisJigsawPieceEntry emptyEntry(int weight) {
        return new IrisJigsawPieceEntry().setEmpty(true).setWeight(weight);
    }

    private static IrisJigsawConnector connector(IrisPosition position, IrisDirection direction, String pool,
                                                 String name, String targetName) {
        return new IrisJigsawConnector()
                .setPosition(position)
                .setDirection(direction)
                .setPool(pool)
                .setName(name)
                .setTargetName(targetName);
    }

    private static void assertCodes(StructureGraphCompilation result, StructureGraphDiagnostic.Code... expected) {
        List<StructureGraphDiagnostic.Code> actual = new ArrayList<>();
        for (StructureGraphDiagnostic diagnostic : result.getDiagnostics()) {
            if (!actual.contains(diagnostic.code())) {
                actual.add(diagnostic.code());
            }
        }
        for (StructureGraphDiagnostic.Code code : expected) {
            assertTrue("Missing diagnostic " + code + " in " + actual, actual.contains(code));
        }
    }

    private static final class InMemoryResolver implements StructureGraphResolver {
        private final Map<String, IrisJigsawPool> pools = new LinkedHashMap<>();
        private final Map<String, IrisJigsawPiece> pieces = new LinkedHashMap<>();
        private final Map<String, IrisObject> objects = new LinkedHashMap<>();

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
