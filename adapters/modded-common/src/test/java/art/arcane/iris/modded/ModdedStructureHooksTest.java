package art.arcane.iris.modded;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModdedStructureHooksTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void objectFeaturesMatchPaperGroups() {
        assertEquals("trees", ModdedStructureHooks.classifyFeature(Feature.TREE));
        assertEquals("fallen_trees", ModdedStructureHooks.classifyFeature(Feature.FALLEN_TREE));
        assertEquals("mushrooms", ModdedStructureHooks.classifyFeature(Feature.HUGE_BROWN_MUSHROOM));
        assertEquals("mushrooms", ModdedStructureHooks.classifyFeature(Feature.HUGE_RED_MUSHROOM));
        assertNull(ModdedStructureHooks.classifyFeature(Feature.ORE));
    }

    @Test
    public void structureSpanGuardUsesInclusiveBoundingBoxDimensions() {
        BoundingBox box = new BoundingBox(-16, -64, 32, 15, 63, 47);

        assertTrue(ModdedStructureHooks.isWithinSpan(box, 128));
        assertFalse(ModdedStructureHooks.isWithinSpan(box, 127));
        assertTrue(ModdedStructureHooks.isWithinSpan(box, 0));
        assertTrue(ModdedStructureHooks.isWithinSpan(box, -1));
    }

    @Test
    public void chunkGridSizeCountsEveryChunkPlacementWouldLoad() {
        assertEquals(1, ModdedStructureHooks.chunkGridSize(new BoundingBox(0, 0, 0, 15, 15, 15)));
        assertEquals(4, ModdedStructureHooks.chunkGridSize(new BoundingBox(0, 0, 0, 16, 15, 16)));
        assertEquals(9, ModdedStructureHooks.chunkGridSize(new BoundingBox(-16, 0, -16, 16, 15, 16)));
    }

    @Test
    public void chunkGridSizeSaturatesInsteadOfOverflowing() {
        BoundingBox box = new BoundingBox(
                Integer.MIN_VALUE / 2, 0, Integer.MIN_VALUE / 2,
                Integer.MAX_VALUE / 2, 15, Integer.MAX_VALUE / 2);

        assertEquals(Integer.MAX_VALUE, ModdedStructureHooks.chunkGridSize(box));
        assertTrue(ModdedStructureHooks.chunkGridSize(box) > ModdedStructureHooks.MAX_PLACEMENT_CHUNKS);
    }

    @Test
    public void structurePlacementReturnsPaperOrderedBounds() {
        BoundingBox box = new BoundingBox(-16, -64, 32, 15, 63, 47);

        assertArrayEquals(new int[]{-16, -64, 32, 15, 63, 47}, ModdedStructureHooks.bounds(box));
    }

    @Test
    public void platformWorldMaximumHeightIsExclusive() {
        assertEquals(320, ModdedPlatformWorld.exclusiveMaxHeight(-64, 384));
        assertEquals(384, ModdedPlatformWorld.exclusiveMaxHeight(0, 384));
    }

    @Test
    public void structureRegistryHooksRejectUnavailableServer() {
        ModdedStructureHooks hooks = new ModdedStructureHooks(() -> null);

        IllegalStateException structureKeys = assertThrows(
                IllegalStateException.class, hooks::structureKeys);
        IllegalStateException jigsawStructureKeys = assertThrows(
                IllegalStateException.class, hooks::jigsawStructureKeys);
        IllegalStateException templatePoolKeys = assertThrows(
                IllegalStateException.class, hooks::templatePoolKeys);
        IllegalStateException structureSetKeys = assertThrows(
                IllegalStateException.class, hooks::structureSetKeys);
        IllegalStateException structureBiomeKeys = assertThrows(
                IllegalStateException.class, () -> hooks.structureBiomeKeys("minecraft:village"));

        assertTrue(structureKeys.getMessage().contains("before the Minecraft server is available"));
        assertTrue(jigsawStructureKeys.getMessage().contains("before the Minecraft server is available"));
        assertTrue(templatePoolKeys.getMessage().contains("before the Minecraft server is available"));
        assertTrue(structureSetKeys.getMessage().contains("before the Minecraft server is available"));
        assertTrue(structureBiomeKeys.getMessage().contains("before the Minecraft server is available"));
    }

    @Test
    public void structureReachabilityHooksRejectUnavailableLevel() {
        ModdedStructureHooks hooks = new ModdedStructureHooks(() -> null);

        IllegalStateException reachable = assertThrows(
                IllegalStateException.class, () -> hooks.reachableStructureKeys(null));
        IllegalStateException possibleBiomes = assertThrows(
                IllegalStateException.class, () -> hooks.possibleBiomeKeys(null));

        assertTrue(reachable.getMessage().contains("without a bound modded ServerLevel"));
        assertTrue(possibleBiomes.getMessage().contains("without a bound modded ServerLevel"));
    }

    @Test
    public void structureRegistryHooksPreserveServerSupplierFailure() {
        IllegalStateException cause = new IllegalStateException("server supplier failed");
        ModdedStructureHooks hooks = new ModdedStructureHooks(() -> {
            throw cause;
        });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, hooks::structureKeys);

        assertTrue(failure.getMessage().contains("access the Minecraft server"));
        assertEquals(cause, failure.getCause());
    }
}
