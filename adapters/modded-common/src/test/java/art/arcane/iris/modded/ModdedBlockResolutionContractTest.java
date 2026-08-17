package art.arcane.iris.modded;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The SPI splits block resolution into a null-returning lookup and an air-falling-back lookup. Modded used to
 * collapse both onto air, so an unknown key produced no output at all.
 */
public class ModdedBlockResolutionContractTest {
    private static final String UNKNOWN = "minecraft:definitely_not_a_real_block";

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void getOrNullReturnsNullForUnknownKey() {
        assertNull(ModdedBlockResolution.getOrNull(UNKNOWN));
        assertNull(ModdedBlockResolution.getOrNull(UNKNOWN, true));
    }

    @Test
    public void getFallsBackToAirForUnknownKey() {
        ModdedBlockState state = ModdedBlockResolution.get(UNKNOWN);
        assertNotNull(state);
        assertEquals(Blocks.AIR, state.handle().getBlock());
    }

    @Test
    public void getOrNullResolvesKnownKeyWithProperties() {
        ModdedBlockState state = ModdedBlockResolution.getOrNull("minecraft:oak_log[axis=x]", true);
        assertNotNull(state);
        assertEquals(Blocks.OAK_LOG, state.handle().getBlock());
    }

    @Test
    public void unknownPropertyFallsBackToDefaultState() {
        ModdedBlockState state = ModdedBlockResolution.getOrNull("minecraft:oak_log[not_a_property=x]", true);
        assertNotNull(state);
        assertEquals(Blocks.OAK_LOG, state.handle().getBlock());
    }

    @Test
    public void cactusPlacementRequiresNativeCactusSupport() {
        assertTrue(ModdedBlockResolution.canPlaceOnto(Blocks.CACTUS, Blocks.CACTUS));
        assertTrue(ModdedBlockResolution.canPlaceOnto(Blocks.CACTUS, Blocks.SAND));
        assertTrue(ModdedBlockResolution.canPlaceOnto(Blocks.CACTUS, Blocks.RED_SAND));
        assertFalse(ModdedBlockResolution.canPlaceOnto(Blocks.CACTUS, Blocks.STONE));
        assertTrue(ModdedBlockResolution.isDecorant(Blocks.CACTUS.defaultBlockState()));
    }
}
