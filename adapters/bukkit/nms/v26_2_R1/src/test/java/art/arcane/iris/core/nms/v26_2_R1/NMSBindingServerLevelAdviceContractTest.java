package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NMSBindingServerLevelAdviceContractTest {
    @Test
    public void serverLevelAdviceSupportsCurrentSpigotAndPaperConstructorLayouts() throws Exception {
        String source = Files.readString(
                Path.of(System.getProperty("iris.nmsBindingSource")));
        String transformer = section(
                source,
                "public boolean injectBukkit()",
                "public void ensureServerLevelInjection()");
        String advice = section(
                source,
                "private static class ServerLevelAdvice",
                "\n    }\n}");

        assertTrue(transformer.contains("takesArgument(0, MinecraftServer.class)"));
        assertTrue(transformer.contains("takesArgument(5, LevelStem.class)"));
        assertFalse(transformer.contains("takesArgument(12, ChunkGenerator.class)"));
        assertFalse(transformer.contains("takesArgument(13, ChunkGenerator.class)"));
        assertTrue(advice.contains("@Advice.AllArguments Object[] constructorArguments"));
        assertTrue(advice.contains("argument instanceof ChunkGenerator candidate"));
        assertFalse(advice.contains("@Advice.Argument(12)"));
        assertFalse(advice.contains("@Advice.Argument(13)"));
    }

    @Test
    public void currentSpigotStorageAccessReceivesManagedIdentityBeforeConstruction() throws Exception {
        String source = Files.readString(
                Path.of(System.getProperty("iris.nmsBindingSource")));
        String transformer = section(
                source,
                "public boolean injectBukkit()",
                "public void ensureServerLevelInjection()");
        String advice = section(
                source,
                "private static class LevelStorageAccessAdvice",
                "private static class ServerLevelAdvice");

        assertTrue(transformer.contains(
                ".type(ElementMatchers.is(LevelStorageSource.LevelStorageAccess.class))"));
        assertTrue(transformer.contains("Advice.to(LevelStorageAccessAdvice.class)"));
        assertTrue(transformer.contains("ElementMatchers.takesArguments(4)"));
        assertTrue(transformer.contains(
                "ElementMatchers.takesArgument(0, LevelStorageSource.class)"));
        assertTrue(transformer.contains("ElementMatchers.takesArgument(1, String.class)"));
        assertTrue(transformer.contains("ElementMatchers.takesArgument(2, Path.class)"));
        assertTrue(transformer.contains("ElementMatchers.takesArgument(3, ResourceKey.class)"));
        assertTrue(advice.contains("@Advice.Argument(1) String levelId"));
        assertTrue(advice.contains(
                "@Advice.Argument(value = 3, readOnly = false) ResourceKey<LevelStem> dimensionType"));
        assertTrue(advice.contains("getDeclaredMethod(\"peekStemGenerator\", String.class)"));
        assertTrue(advice.contains(".invoke(null, levelId)"));
        assertTrue(advice.contains(
                "dimensionType = ResourceKey.create(Registries.LEVEL_STEM, worldIdentifier);"));
        assertFalse(advice.contains("dimensionRoot("));
        assertFalse(advice.contains("Files.copy"));
    }

    @Test
    public void ownedWorldPublishesItsManagedIdentityAndRuntimeStemTogether() throws Exception {
        String source = Files.readString(
                Path.of(System.getProperty("iris.nmsBindingSource")));
        String advice = section(
                source,
                "private static class ServerLevelAdvice",
                "\n    }\n}");

        assertTrue(advice.contains(
                "@Advice.Argument(value = 4, readOnly = false) ResourceKey<Level> dimensionKey"));
        assertTrue(advice.contains("getMethod(\"getTarget\")"));
        assertTrue(advice.contains("getMethod(\"getWorld\")"));
        assertTrue(advice.contains("getMethod(\"identity\")"));
        assertTrue(advice.contains("\"iris\".equals(worldIdentifier.getNamespace())"));
        assertTrue(advice.contains("\"minecraft\".equals(worldIdentifier.getNamespace())"));

        int resolvedStem = advice.indexOf("Object resolvedStem = stemMethod.invoke(");
        int validatedStem = advice.indexOf("resolvedStem instanceof LevelStem runtimeStem");
        int identityPublication = advice.indexOf(
                "dimensionKey = ResourceKey.create(Registries.DIMENSION, worldIdentifier);");
        int stemPublication = advice.indexOf("levelStem = runtimeStem;");
        assertTrue(resolvedStem >= 0);
        assertTrue(validatedStem > resolvedStem);
        assertTrue(identityPublication > validatedStem);
        assertTrue(stemPublication > identityPublication);
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue("Missing source section starting with " + startMarker, start >= 0);
        assertTrue("Missing source section ending with " + endMarker, end > start);
        return source.substring(start, end);
    }
}
