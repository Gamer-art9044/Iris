package art.arcane.iris.modded;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class ModdedDynamicLevelLifecycleContractTest {
    private static final String SOURCE_ROOT_PROPERTY = "iris.moddedCommonSources";

    @Test
    public void sharedManagerPublishesDynamicLoadAndUnloadAroundRegistration() throws IOException {
        String manager = commonSource("ModdedDimensionManager.java");
        String loader = commonSource("ModdedLoader.java");
        String injection = method(manager, "private static Handle inject(");
        String removal = method(manager, "public static boolean remove(");
        String injectionRollback = method(manager, "private static void rollbackInjection(");
        String removalRollback = method(manager, "private static void rollbackRemoval(");

        assertTrue(loader.contains("void fireDynamicLevelLoad(MinecraftServer server, ServerLevel level);"));
        assertTrue(loader.contains("void fireDynamicLevelUnload(MinecraftServer server, ServerLevel level);"));
        assertBefore(injection, "serverAccess.putLevelIfAbsent(server, key, level);",
                "fireDynamicLevelLoad(server, level);");
        assertBefore(removal, "fireDynamicLevelUnload(server, level);",
                "serverAccess.removeLevel(server, key);");
        assertTrue(injectionRollback.contains("fireDynamicLevelUnload(server, level);"));
        assertTrue(removalRollback.contains("fireDynamicLevelLoad(server, level);"));
    }

    @Test
    public void everyLoaderUsesItsNativeLifecycleBus() throws IOException {
        String fabric = loaderSource("fabric", "art/arcane/iris/fabric/FabricModdedLoader.java");
        String forge = loaderSource("forge", "art/arcane/iris/forge/ForgeModdedLoader.java");
        String neoForge = loaderSource("neoforge", "art/arcane/iris/neoforge/NeoForgeModdedLoader.java");

        assertTrue(fabric.contains("ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);"));
        assertTrue(fabric.contains("ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);"));
        assertTrue(forge.contains("LevelEvent.Load.BUS.post(new LevelEvent.Load(level));"));
        assertTrue(forge.contains("LevelEvent.Unload.BUS.post(new LevelEvent.Unload(level));"));
        assertTrue(neoForge.contains("NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));"));
        assertTrue(neoForge.contains("NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));"));
    }

    private static String commonSource(String file) throws IOException {
        return Files.readString(commonRoot().resolve("art/arcane/iris/modded").resolve(file))
                .replace("\r\n", "\n");
    }

    private static String loaderSource(String loader, String relative) throws IOException {
        Path adaptersRoot = commonRoot().getParent().getParent().getParent().getParent();
        return Files.readString(adaptersRoot.resolve(loader).resolve("src/main/java").resolve(relative))
                .replace("\r\n", "\n");
    }

    private static Path commonRoot() {
        String root = System.getProperty(SOURCE_ROOT_PROPERTY);
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("Missing test property " + SOURCE_ROOT_PROPERTY);
        }
        return Path.of(root);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new IllegalArgumentException("Missing source method " + signature);
        }
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new IllegalArgumentException("Unclosed source method " + signature);
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue("Missing source token " + first, firstIndex >= 0);
        assertTrue("Missing source token " + second, secondIndex >= 0);
        assertTrue(first + " must precede " + second, firstIndex < secondIndex);
    }
}
