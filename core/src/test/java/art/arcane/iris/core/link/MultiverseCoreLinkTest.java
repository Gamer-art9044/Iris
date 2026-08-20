package art.arcane.iris.core.link;

import org.bukkit.World;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MultiverseCoreLinkTest {
    @Test
    public void multiverseRegistrationRequiresTheConfiguredWorldName() throws Exception {
        Method updateWorld = MultiverseCoreLink.class
                .getDeclaredMethod("updateWorld", World.class, String.class, String.class);

        assertEquals(void.class, updateWorld.getReturnType());
        assertFalse("updateWorld must not keep an overload that derives the name from the live world",
                Arrays.stream(MultiverseCoreLink.class.getDeclaredMethods())
                        .anyMatch(method -> "updateWorld".equals(method.getName())
                                && method.getParameterCount() == 2));
    }

    @Test
    public void registrationDoesNotRewriteTheRecordedMultiverseName() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/link/MultiverseCoreLink.java"));

        assertFalse("worlds are created under the startup name, so the recorded name needs no correction",
                source.contains("setLegacyWorldName"));
        assertTrue("Multiverse still has to be told the live world name it will record",
                source.contains("ImportWorldOptions.worldName(world.getName())"));
    }

    @Test
    public void lookupNamesAddTheIrisKeyForConfiguredStartupNames() {
        assertEquals(
                List.of("world_iris_mvtest", "iris:mvtest"),
                MultiverseCoreLink.lookupNames("world_iris_mvtest", "world")
        );
        assertEquals(
                List.of("survival_iris_mvtest", "iris:mvtest"),
                MultiverseCoreLink.lookupNames("survival_iris_mvtest", "survival")
        );
    }

    @Test
    public void lookupNamesKeepNonConfiguredNamesUntouched() {
        assertEquals(List.of("iris_mvtest"), MultiverseCoreLink.lookupNames("iris_mvtest", "world"));
        assertEquals(List.of("world"), MultiverseCoreLink.lookupNames("world", "world"));
        assertEquals(List.of("iris:mvtest"), MultiverseCoreLink.lookupNames("iris:mvtest", "world"));
        assertEquals(List.of("iris_studio-demo"), MultiverseCoreLink.lookupNames("iris_studio-demo", "world"));
    }
}
