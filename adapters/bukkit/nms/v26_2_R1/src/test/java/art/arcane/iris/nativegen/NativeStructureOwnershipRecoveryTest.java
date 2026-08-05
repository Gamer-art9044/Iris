package art.arcane.iris.nativegen;

import art.arcane.iris.engine.framework.NativeStructureOwnershipRecord;
import art.arcane.iris.engine.framework.NativeStructureStartPlan;
import art.arcane.iris.engine.framework.StructurePlacementGrid;
import art.arcane.iris.engine.object.IrisNativeStructure;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.IrisStructureTerrain;
import art.arcane.iris.engine.object.IrisStructureTerrainMode;
import art.arcane.volmlib.util.collection.KList;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NativeStructureOwnershipRecoveryTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void missingSidecarRecoversExactPolicyEnvelopeAndLocatorAfterReload() {
        long seed = 5519231L;
        ChunkPos origin = new ChunkPos(7, -5);
        OceanMonumentStructure structure = structure();
        StructureStart expected = monumentStart(structure, origin, seed);
        NativeStructureVerticalPlacer.alignOceanMonumentToSeaLevel(
                expected, 0, 80, -64, 320);
        int expectedLocatorY = NativeStructureOwnershipFingerprint.locatorY(expected);
        NativeStructureStartPlan plan = plan(origin, "recovery-exact", 24);
        StructureStart reloaded = new StructureStart(
                structure,
                origin,
                expected.getReferences(),
                OceanMonumentStructure.regeneratePiecesAfterLoad(
                        origin, seed, new PiecesContainer(expected.getPieces()))
        );

        NativeStructureOwnershipRecord recovered =
                NativeStructureOwnershipRecovery.proveCandidate(
                        "minecraft:monument", structure, reloaded,
                        plan, expected, false);

        assertNotNull(recovered);
        assertEquals(StructurePlacementGrid.placementIdentity(plan.placement()),
                recovered.placementIdentity());
        assertEquals(expectedLocatorY, recovered.locatorY());
        assertNotEquals(NativeStructureOwnershipFingerprint.locatorY(reloaded),
                recovered.locatorY());
        assertEquals(IrisStructureTerrainMode.FORCE_CARVE,
                recovered.restoredDecision().terrain().resolvedMode());
        assertEquals(24, recovered.restoredDecision().terrain().getHorizontalPadding());
        assertTrue(recovered.referenceMinChunkX()
                < NativeStructureReferenceEnvelope.contentBounds(reloaded).minX() >> 4);
    }

    @Test
    public void naturalSameKeyStartRemainsUnownedWhenOriginIsAmbiguous() {
        long seed = 22817L;
        ChunkPos origin = new ChunkPos(-3, 6);
        OceanMonumentStructure structure = structure();
        StructureStart natural = monumentStart(structure, origin, seed);
        NativeStructureStartPlan plan = plan(origin, "natural-ambiguous", 0);

        NativeStructureOwnershipRecord recovered =
                NativeStructureOwnershipRecovery.proveCandidate(
                        "minecraft:monument", structure, natural,
                        plan, natural, true);

        assertNull(recovered);
    }

    @Test
    public void changedPlacementIdentityOrGeometryCannotRecoverOwnership() {
        long seed = 991723L;
        ChunkPos origin = new ChunkPos(2, 4);
        OceanMonumentStructure structure = structure();
        StructureStart expected = monumentStart(structure, origin, seed);
        StructureStart changedGeometry = monumentStart(structure, origin, seed);
        for (StructurePiece piece : changedGeometry.getPieces()) {
            piece.move(1, 0, 0);
        }
        NativeStructureStartPlan originalIdentity = plan(
                origin, "original-identity", 0);
        NativeStructureStartPlan changedIdentity = plan(
                new ChunkPos(origin.x() + 1, origin.z()), "changed-identity", 0);

        assertNotEquals(
                StructurePlacementGrid.placementIdentity(originalIdentity.placement()),
                StructurePlacementGrid.placementIdentity(changedIdentity.placement()));
        assertNull(NativeStructureOwnershipRecovery.proveCandidate(
                "minecraft:monument", structure, expected,
                changedIdentity, expected, false));
        assertNull(NativeStructureOwnershipRecovery.proveCandidate(
                "minecraft:monument", structure, changedGeometry,
                plan(origin, "geometry-check", 0), expected, false));
    }

    @Test
    public void recoveredEnvelopeStillRequiresExactFingerprint() {
        long seed = 81337L;
        ChunkPos origin = new ChunkPos(-8, -9);
        OceanMonumentStructure structure = structure();
        StructureStart expected = monumentStart(structure, origin, seed);
        NativeStructureOwnershipRecord recovered =
                NativeStructureOwnershipRecovery.proveCandidate(
                        "minecraft:monument", structure, expected,
                        plan(origin, "fingerprint-check", 16), expected, false);
        assertNotNull(recovered);

        StructureStart moved = monumentStart(structure, origin, seed);
        for (StructurePiece piece : moved.getPieces()) {
            piece.move(0, 0, 1);
        }

        assertFalse(NativeStructureOwnershipFingerprint.matches(recovered, moved));
    }

    private static OceanMonumentStructure structure() {
        return new OceanMonumentStructure(
                new OceanMonumentStructure.StructureSettings(HolderSet.empty()));
    }

    private static StructureStart monumentStart(OceanMonumentStructure structure,
                                                 ChunkPos origin, long seed) {
        WorldgenRandom random = new WorldgenRandom(
                new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setLargeFeatureSeed(seed, origin.x(), origin.z());
        Direction orientation = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        OceanMonumentPieces.MonumentBuilding building =
                new OceanMonumentPieces.MonumentBuilding(
                        random,
                        origin.getMinBlockX() - 29,
                        origin.getMinBlockZ() - 29,
                        orientation
                );
        return new StructureStart(
                structure, origin, 0, new PiecesContainer(List.of(building)));
    }

    private static NativeStructureStartPlan plan(ChunkPos origin,
                                                  String placementId,
                                                  int horizontalPadding) {
        IrisNativeStructure source = new IrisNativeStructure()
                .setStructure("minecraft:monument")
                .setWeight(1);
        IrisStructureTerrain terrain = new IrisStructureTerrain()
                .setMode(IrisStructureTerrainMode.FORCE_CARVE)
                .setHorizontalPadding(horizontalPadding);
        IrisStructurePlacement placement = new IrisStructurePlacement()
                .setPlacementId(placementId)
                .setTerrain(terrain)
                .setNativeStructures(new KList<IrisNativeStructure>().qadd(source));
        return new NativeStructureStartPlan(
                placement,
                source,
                origin.x(),
                origin.z(),
                NativeStructureReferenceEnvelope.contentBounds(
                        monumentStart(structure(), origin, 1L)).minY()
        );
    }
}
