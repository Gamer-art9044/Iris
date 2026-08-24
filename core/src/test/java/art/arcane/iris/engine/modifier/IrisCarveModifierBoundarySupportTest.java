package art.arcane.iris.engine.modifier;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimensionCarvingResolver;
import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveHydrology;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.volmlib.util.matter.MatterSlice;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class IrisCarveModifierBoundarySupportTest {
    @Test
    @SuppressWarnings("unchecked")
    public void boundaryBiomeUsesCustomMatterAtItsOwnY() {
        IrisBiome customFloor = mock(IrisBiome.class);
        IrisBiome resolvedCeiling = mock(IrisBiome.class);
        IrisData data = mock(IrisData.class);
        ResourceLoader<IrisBiome> biomeLoader = mock(ResourceLoader.class);
        doReturn(biomeLoader).when(data).getBiomeLoader();
        doReturn(customFloor).when(biomeLoader).load("custom/floor");

        Engine engine = mock(Engine.class);
        doReturn(data).when(engine).getData();
        doReturn(resolvedCeiling).when(engine).getCaveBiome(anyInt(), eq(42), anyInt(), any(IrisDimensionCarvingResolver.State.class));

        IrisCarveModifier modifier = mock(IrisCarveModifier.class, CALLS_REAL_METHODS);
        doReturn(engine).when(modifier).getEngine();

        MantleChunk<Matter> mantleChunk = mock(MantleChunk.class);
        Matter floorMatter = mock(Matter.class);
        Matter ceilingMatter = mock(Matter.class);
        MatterSlice<MatterCavern> floorSlice = mock(MatterSlice.class);
        MatterSlice<MatterCavern> ceilingSlice = mock(MatterSlice.class);
        doReturn(true).when(mantleChunk).exists(0);
        doReturn(true).when(mantleChunk).exists(2);
        doReturn(floorMatter).when(mantleChunk).get(0);
        doReturn(ceilingMatter).when(mantleChunk).get(2);
        doReturn(true).when(floorMatter).hasSlice(MatterCavern.class);
        doReturn(true).when(ceilingMatter).hasSlice(MatterCavern.class);
        doReturn(floorSlice).when(floorMatter).getSlice(MatterCavern.class);
        doReturn(ceilingSlice).when(ceilingMatter).getSlice(MatterCavern.class);
        doReturn(new MatterCavern(true, "custom/floor", (byte) 0)).when(floorSlice).get(1, 6, 2);
        doReturn(null).when(ceilingSlice).get(1, 10, 2);

        Long2ObjectOpenHashMap<IrisBiome> caveBiomeCache = new Long2ObjectOpenHashMap<>();
        Map<String, IrisBiome> customBiomeCache = new HashMap<>();
        IrisDimensionCarvingResolver.State resolverState = new IrisDimensionCarvingResolver.State();

        IrisBiome floor = modifier.resolveCaveBoundaryBiome(
                mantleChunk, 1, 6, 2, 40, 44, resolverState, caveBiomeCache, customBiomeCache);
        IrisBiome ceiling = modifier.resolveCaveBoundaryBiome(
                mantleChunk, 1, 42, 2, 40, 44, resolverState, caveBiomeCache, customBiomeCache);

        assertSame(customFloor, floor);
        assertSame(resolvedCeiling, ceiling);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void gravityFloorLayerRequiresSolidSupportBelowItsTarget() {
        Hunk<PlatformBlockState> output = mock(Hunk.class);
        PlatformBlockState air = state("minecraft:cave_air", false);
        PlatformBlockState solid = state("minecraft:stone", true);
        PlatformBlockState sand = state("minecraft:sand", true);
        PlatformBlockState stone = state("minecraft:stone", true);

        doReturn(air).when(output).getRaw(0, 4, 0);
        assertFalse(IrisCarveModifier.canReplaceCaveFloorLayer(output, 0, 5, 0, sand));
        assertTrue(IrisCarveModifier.canReplaceCaveFloorLayer(output, 0, 5, 0, stone));

        doReturn(solid).when(output).getRaw(0, 4, 0);
        assertTrue(IrisCarveModifier.canReplaceCaveFloorLayer(output, 0, 5, 0, sand));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void gravityFloorDoesNotReceiveDecoratorsOverLowerCaveAir() {
        Hunk<PlatformBlockState> output = mock(Hunk.class);
        PlatformBlockState air = state("minecraft:cave_air", false);
        PlatformBlockState sand = state("minecraft:sand", true);
        PlatformBlockState stone = state("minecraft:stone", true);

        doReturn(sand).when(output).getRaw(0, 5, 0);
        doReturn(air).when(output).getRaw(0, 4, 0);
        assertFalse(IrisCarveModifier.hasStableCaveFloorSupport(output, 0, 6, 0));

        doReturn(stone).when(output).getRaw(0, 4, 0);
        assertTrue(IrisCarveModifier.hasStableCaveFloorSupport(output, 0, 6, 0));
    }

    @Test
    public void riverGuardOnlyAcceptsStableSolidBoundaryLayers() {
        RiverCaveHydrology guard = RiverCaveHydrology.of(RiverCaveAction.SEAL_GUARD);
        PlatformBlockState stone = state("minecraft:stone", true);
        PlatformBlockState sand = state("minecraft:sand", true);
        PlatformBlockState water = state("minecraft:water", false, true);

        assertTrue(IrisCarveModifier.canReplaceRiverGuard(guard, stone, false));
        assertTrue(IrisCarveModifier.canReplaceRiverGuard(guard, stone, true));
        assertTrue(IrisCarveModifier.canReplaceRiverGuard(guard, sand, false));
        assertFalse(IrisCarveModifier.canReplaceRiverGuard(guard, sand, true));
        assertFalse(IrisCarveModifier.canReplaceRiverGuard(guard, water, false));
    }

    @Test
    public void riverBiomeInheritanceIsColumnCoherentAndHonorsLimits() {
        long seed = 7845123L;
        boolean cell = IrisCarveModifier.selectsParentRiverBiome(seed, 8, 12, 0.5D);
        for (int x = 8; x < 12; x++) {
            for (int z = 12; z < 16; z++) {
                assertTrue(cell == IrisCarveModifier.selectsParentRiverBiome(seed, x, z, 0.5D));
            }
        }
        assertFalse(IrisCarveModifier.selectsParentRiverBiome(seed, 8, 12, 0D));
        assertTrue(IrisCarveModifier.selectsParentRiverBiome(seed, 8, 12, 1D));

        boolean inherited = false;
        boolean overridden = false;
        for (int x = -128; x <= 128; x += 4) {
            boolean selected = IrisCarveModifier.selectsParentRiverBiome(seed, x, 0, 0.5D);
            inherited |= selected;
            overridden |= !selected;
        }
        assertTrue(inherited);
        assertTrue(overridden);
    }

    private PlatformBlockState state(String key, boolean solid) {
        return state(key, solid, false);
    }

    private PlatformBlockState state(String key, boolean solid, boolean fluid) {
        PlatformBlockState state = mock(PlatformBlockState.class);
        doReturn(key).when(state).key();
        doReturn(solid).when(state).isSolid();
        doReturn(fluid).when(state).isFluid();
        return state;
    }
}
