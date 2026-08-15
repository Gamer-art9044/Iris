package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.core.nms.INMSBinding;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NMSBindingCurrentPaperWorldDataContractTest {
    @Test
    public void stagesAllCurrentPaperWorldDataFromLiveServerState() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsBindingSource")));
        String writer = section(
                source,
                "public void writeCurrentPaperWorldData(",
                "public KMap<Material, List<BlockProperty>> getBlockProperties()"
        );

        assertTrue(writer.contains("WorldReplacementSeed.copyWithAuthoritativeSeed("));
        assertTrue(writer.contains("UUID metadataUuid = UUID.randomUUID()"));
        assertTrue(writer.contains("new PaperWorldMetadata(metadataUuid)"));
        assertTrue(writer.contains("captureCurrentPaperLevelOverrides(craftServer, server)"));
        assertTrue(writer.contains("new SavedDataStorage("));
        assertTrue(writer.contains("server.getFixerUpper()"));
        assertTrue(writer.contains("server.registryAccess()"));
        assertTrue(writer.contains("data/minecraft/world_gen_settings.dat"));
        assertTrue(writer.contains("data/paper/metadata.dat"));
        assertTrue(writer.contains("data/paper/level_overrides.dat"));
        assertTrue(writer.contains("WorldReplacementSeed.readAuthoritativeSeed(targetWorld)"));
        assertTrue(writer.contains("verificationStorage.get(PaperWorldMetadata.TYPE)"));
        assertTrue(writer.contains("metadataUuid.equals(metadata.uuid())"));
        assertTrue(writer.contains("verificationStorage.get(PaperLevelOverrides.TYPE)"));
        assertTrue(writer.contains("overrides == null || overrides.isInitialized()"));
        assertTrue(writer.contains("Files.isRegularFile(requiredDataFile, LinkOption.NOFOLLOW_LINKS)"));
        assertFalse(writer.toLowerCase().contains("migrat"));
        assertFalse(writer.toLowerCase().contains("fallback"));
    }

    @Test
    public void capturesOnlyLiveLevelOverridesOnTheGlobalThread() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsBindingSource")));
        String capture = section(
                source,
                "private PaperLevelOverrides captureCurrentPaperLevelOverrides(",
                "private PaperLevelOverrides createCurrentPaperLevelOverrides("
        );
        String create = section(
                source,
                "private PaperLevelOverrides createCurrentPaperLevelOverrides(",
                "public KMap<Material, List<BlockProperty>> getBlockProperties()"
        );

        assertTrue(capture.contains("craftServer.isGlobalTickThread()"));
        assertTrue(capture.contains("J.isFolia() && J.isPrimaryThread()"));
        assertTrue(capture.contains("J.runGlobal("));
        assertTrue(capture.contains("captured.get(CURRENT_WORLD_DATA_SNAPSHOT_TIMEOUT_SECONDS"));
        assertTrue(capture.contains("Thread.currentThread().interrupt()"));
        assertTrue(create.contains("PaperLevelOverrides.createFromLiveLevelData(primaryLevelData)"));
        assertFalse(capture.contains("WorldReplacementSeed"));
        assertFalse(capture.contains("SavedDataStorage"));
        assertFalse(capture.contains("Files."));
    }

    @Test
    public void unsupportedBindingsRejectCurrentPaperWorldDataStaging() {
        INMSBinding binding = (INMSBinding) Proxy.newProxyInstance(
                INMSBinding.class.getClassLoader(),
                new Class<?>[]{INMSBinding.class},
                (proxy, method, arguments) -> InvocationHandler.invokeDefault(proxy, method, arguments)
        );

        UnsupportedOperationException error = assertThrows(
                UnsupportedOperationException.class,
                () -> binding.writeCurrentPaperWorldData(Path.of("source"), Path.of("target"), 1L)
        );
        assertTrue(error.getMessage().contains("does not support current Paper world data staging"));
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue("Missing source section starting with " + startMarker, start >= 0);
        assertTrue("Missing source section ending with " + endMarker, end > start);
        return source.substring(start, end);
    }
}
