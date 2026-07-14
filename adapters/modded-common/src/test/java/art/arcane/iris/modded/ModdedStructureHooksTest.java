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
    public void structurePlacementReturnsPaperOrderedBounds() {
        BoundingBox box = new BoundingBox(-16, -64, 32, 15, 63, 47);

        assertArrayEquals(new int[]{-16, -64, 32, 15, 63, 47}, ModdedStructureHooks.bounds(box));
    }

    @Test
    public void platformWorldMaximumHeightIsExclusive() {
        assertEquals(320, ModdedPlatformWorld.exclusiveMaxHeight(-64, 384));
        assertEquals(384, ModdedPlatformWorld.exclusiveMaxHeight(0, 384));
    }
}
