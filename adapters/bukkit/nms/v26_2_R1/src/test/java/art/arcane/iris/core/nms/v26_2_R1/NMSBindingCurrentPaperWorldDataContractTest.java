package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.core.nms.INMSBinding;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NMSBindingCurrentPaperWorldDataContractTest {
    @Test
    public void bindingDelegatesWithoutLinkingPaperSavedDataClasses() throws Exception {
        String bindingSource = Files.readString(bindingSourcePath());
        String writer = section(
                bindingSource,
                "public void writeCurrentPaperWorldData(",
                "public boolean awaitServerShutdownBoundary("
        );

        assertTrue(writer.contains("CurrentPaperWorldDataWriter.write("));
        assertFalse(bindingSource.contains("PaperWorldMetadata"));
        assertFalse(bindingSource.contains("PaperLevelOverrides"));
        assertFalse(bindingSource.contains("io.papermc.paper.world.saveddata"));

        InputStream classResource = NMSBindingCurrentPaperWorldDataContractTest.class
                .getResourceAsStream("NMSBinding.class");
        assertNotNull(classResource);
        try (InputStream input = classResource) {
            String classFile = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(classFile.contains("PaperWorldMetadata"));
            assertFalse(classFile.contains("PaperLevelOverrides"));
            assertFalse(classFile.contains("io/papermc/paper/world/saveddata"));
        }
    }

    @Test
    public void stagesAllCurrentPaperWorldDataFromLiveServerState() throws Exception {
        String writer = Files.readString(writerSourcePath());

        assertTrue(writer.contains("WorldReplacementSeed.copyWithAuthoritativeSeed("));
        assertTrue(writer.contains("UUID metadataUuid = UUID.randomUUID()"));
        assertTrue(writer.contains("new PaperWorldMetadata(metadataUuid)"));
        assertTrue(writer.contains("captureLevelOverrides(craftServer, server)"));
        assertTrue(writer.indexOf("captureLevelOverrides(craftServer, server)")
                < writer.indexOf("WorldReplacementSeed.copyWithAuthoritativeSeed("));
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
        String source = Files.readString(writerSourcePath());
        String capture = section(
                source,
                "private static PaperLevelOverrides captureLevelOverrides(",
                "private static PaperLevelOverrides createLevelOverrides("
        );
        String create = section(
                source,
                "private static PaperLevelOverrides createLevelOverrides(",
                "\n    }\n}"
        );

        assertTrue(capture.contains("craftServer.isGlobalTickThread()"));
        assertTrue(capture.contains("J.isFolia() && J.isPrimaryThread()"));
        assertTrue(capture.contains("J.runGlobal("));
        assertTrue(capture.contains("createLevelOverrides(craftServer, server)"));
        assertTrue(capture.contains("captured.get(SNAPSHOT_TIMEOUT_SECONDS"));
        assertTrue(capture.contains("Thread.currentThread().interrupt()"));
        assertTrue(create.contains("if (!craftServer.isGlobalTickThread())"));
        assertTrue(create.indexOf("if (!craftServer.isGlobalTickThread())")
                < create.indexOf("server.getWorldData().overworldData()"));
        assertTrue(create.contains("PaperLevelOverrides.createFromLiveLevelData(primaryLevelData)"));
        assertFalse(capture.contains("WorldReplacementSeed"));
        assertFalse(capture.contains("SavedDataStorage"));
        assertFalse(capture.contains("Files."));
        assertFalse(create.contains("WorldReplacementSeed"));
        assertFalse(create.contains("SavedDataStorage"));
        assertFalse(create.contains("Files."));
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

    private static Path bindingSourcePath() {
        return Path.of(System.getProperty("iris.nmsBindingSource"));
    }

    private static Path writerSourcePath() {
        return bindingSourcePath().resolveSibling("CurrentPaperWorldDataWriter.java");
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue("Missing source section starting with " + startMarker, start >= 0);
        assertTrue("Missing source section ending with " + endMarker, end > start);
        return source.substring(start, end);
    }
}
