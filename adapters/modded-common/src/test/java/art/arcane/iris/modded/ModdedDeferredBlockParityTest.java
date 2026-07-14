package art.arcane.iris.modded;

import art.arcane.iris.modded.api.ModdedBlockData;
import art.arcane.iris.modded.api.ModdedCustomContentRegistry;
import art.arcane.iris.modded.api.ModdedDataProvider;
import art.arcane.iris.modded.api.ModdedDataType;
import art.arcane.iris.spi.PlatformBlockState;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ModdedDeferredBlockParityTest {
    private static final Identifier BLOCK_ID = Identifier.parse("iris_deferred_test:oak_stairs");

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ModdedCustomContentRegistry.register(new DeferredProvider());
    }

    @Test
    public void deferredProviderStateCarriesPlacementMetadataThroughMutation() {
        ModdedBlockState resolved = ModdedBlockResolution.getOrNull(
                "iris_deferred_test:oak_stairs[facing=north,half=bottom]");

        assertNotNull(resolved);
        assertTrue(resolved.isCustom());
        assertEquals("iris_deferred_test:oak_stairs[facing=north,half=bottom]", resolved.deferredPlacementKey());
        assertEquals(Blocks.OAK_STAIRS, resolved.handle().getBlock());

        PlatformBlockState mutated = resolved.withProperty("facing", "west");
        assertTrue(mutated.isCustom());
        assertEquals(resolved.deferredPlacementKey(), mutated.deferredPlacementKey());
        assertEquals("west", ((ModdedBlockState) mutated).handle().getValue(StairBlock.FACING).getName());

        PlatformBlockState base = mutated.placementBaseState();
        assertFalse(base.isCustom());
        assertEquals(Blocks.OAK_STAIRS, ((ModdedBlockState) base).handle().getBlock());
    }

    private static final class DeferredProvider implements ModdedDataProvider {
        @Override
        public String modId() {
            return "iris_deferred_test";
        }

        @Override
        public Collection<Identifier> getTypes(ModdedDataType type) {
            return type == ModdedDataType.BLOCK ? List.of(BLOCK_ID) : List.of();
        }

        @Override
        public boolean isValidProvider(Identifier id, ModdedDataType type) {
            return type == ModdedDataType.BLOCK && BLOCK_ID.equals(id);
        }

        @Override
        public ModdedBlockData getBlockData(Identifier blockId, Map<String, String> state) {
            return ModdedBlockData.deferred(Blocks.OAK_STAIRS.defaultBlockState());
        }
    }
}
