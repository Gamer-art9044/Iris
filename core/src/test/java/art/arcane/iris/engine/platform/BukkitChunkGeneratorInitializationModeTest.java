package art.arcane.iris.engine.platform;

import art.arcane.iris.engine.IrisEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BukkitChunkGeneratorInitializationModeTest {
    @Test
    public void runtimeAndOrdinaryStudioWarmGenerationCaches() {
        IrisEngine.InitializationMode runtime =
                BukkitChunkGenerator.selectInitializationMode(false, false);
        IrisEngine.InitializationMode studio =
                BukkitChunkGenerator.selectInitializationMode(true, false);

        assertEquals(IrisEngine.InitializationMode.RUNTIME, runtime);
        assertFalse(runtime.studio());
        assertTrue(runtime.warmGenerationCaches());
        assertEquals(IrisEngine.InitializationMode.STUDIO, studio);
        assertTrue(studio.studio());
        assertTrue(studio.warmGenerationCaches());
    }

    @Test
    public void activeJigsawStudioSkipsGenerationCacheWarm() {
        IrisEngine.InitializationMode mode =
                BukkitChunkGenerator.selectInitializationMode(true, true);

        assertEquals(IrisEngine.InitializationMode.JIGSAW_STUDIO, mode);
        assertTrue(mode.studio());
        assertFalse(mode.warmGenerationCaches());
    }

    @Test
    public void jigsawBootstrapAndInitializationFailureSkipNativeStructureGeneration() {
        assertFalse(BukkitChunkGenerator.shouldGenerateNativeStructures(true, false, false));
        assertFalse(BukkitChunkGenerator.shouldGenerateNativeStructures(true, true, false));
        assertFalse(BukkitChunkGenerator.shouldGenerateNativeStructures(false, true, false));
        assertFalse(BukkitChunkGenerator.shouldGenerateNativeStructures(false, false, true));
        assertTrue(BukkitChunkGenerator.shouldGenerateNativeStructures(false, false, false));
    }

    @Test
    public void onlyOrdinaryOpenStudioRunsThePackHotloader() {
        assertFalse(BukkitChunkGenerator.shouldRunStudioHotload(false, false, false));
        assertFalse(BukkitChunkGenerator.shouldRunStudioHotload(true, true, false));
        assertFalse(BukkitChunkGenerator.shouldRunStudioHotload(true, false, true));
        assertTrue(BukkitChunkGenerator.shouldRunStudioHotload(true, false, false));
    }

    @Test
    public void transientStudioWorldsAreNotPersisted() {
        assertFalse(BukkitChunkGenerator.shouldPersistWorldRegistration(true));
        assertTrue(BukkitChunkGenerator.shouldPersistWorldRegistration(false));
    }

}
