package art.arcane.iris.core.runtime;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.service.ObjectStudioSaveService;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudioEntitySpawningTest {
    @Test
    public void studioEntitySpawningDefaultsToEnabled() {
        assertTrue(new IrisSettings.IrisSettingsStudio().isEntitySpawning());
    }

    @Test
    public void studioWorldRulesEnableVanillaMobSpawning() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/WorldRuntimeControlService.java")).replace("\r\n", "\n");
        int helperStart = source.indexOf("static void enableStudioEntitySpawning");
        int helperEnd = source.indexOf("public boolean applyNoonTimeLock", helperStart);
        String helper = source.substring(helperStart, helperEnd);

        assertTrue(source.contains("enableStudioEntitySpawning(world);"));
        assertTrue(helper.contains("setBooleanGameRule(world, true, \"DO_MOB_SPAWNING\""));
        assertTrue(helper.contains("setBooleanGameRule(world, true, \"DO_TRADER_SPAWNING\""));
        assertTrue(helper.contains("setBooleanGameRule(world, true, \"DO_PATROL_SPAWNING\""));
        assertTrue(helper.contains("setBooleanGameRule(world, true, \"DO_INSOMNIA\""));
        assertTrue(helper.contains("setBooleanGameRule(world, true, \"DO_WARDEN_SPAWNING\""));
        assertFalse(helper.contains("setBooleanGameRule(world, false, \"DO_MOB_SPAWNING\""));
    }

    @Test
    public void generalStudioUsesAPlayerModeEligibleForNaturalSpawning() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/project/IrisProject.java")).replace("\r\n", "\n");

        assertFalse(source.contains("GameMode.SPECTATOR"));
        assertTrue(source.contains("GameMode.CREATIVE"));
    }

    @Test
    public void objectStudioDoesNotCancelEntitySpawns() {
        boolean hasSpawnCanceller = Arrays.stream(ObjectStudioSaveService.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("onCreatureSpawn")
                        || method.getName().equals("onEntitySpawn"));

        assertFalse(hasSpawnCanceller);
    }
}
