package art.arcane.iris.core;

import art.arcane.iris.core.commands.CommandStudio;
import art.arcane.iris.core.tools.IrisCreator;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;

public class StudioRuntimeCleanupTest {
    @Test
    public void pregenSettingsNoLongerExposeStartupNoisemapPrebake() {
        boolean found = Arrays.stream(IrisSettings.IrisSettingsPregen.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("startupNoisemapPrebake"));

        assertFalse(found);
    }

    @Test
    public void studioCommandNoLongerExposesProfilecache() {
        boolean found = Arrays.stream(CommandStudio.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("profilecache"));

        assertFalse(found);
    }

    @Test
    public void studioCreatorNoLongerContainsPrewarmOrPrebakeHelpers() {
        boolean found = Arrays.stream(IrisCreator.class.getDeclaredMethods())
                .map(method -> method.getName().toLowerCase())
                .anyMatch(name -> name.contains("prewarm") || name.contains("prebake"));

        assertFalse(found);
    }

    @Test
    public void noisemapPrebakePipelineClassIsRemoved() {
        try {
            Class.forName("art.arcane.iris.engine.IrisNoisemapPrebakePipeline");
        } catch (ClassNotFoundException ignored) {
            return;
        }

        throw new AssertionError("IrisNoisemapPrebakePipeline should not exist.");
    }
}
