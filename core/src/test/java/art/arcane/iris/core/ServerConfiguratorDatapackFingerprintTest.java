package art.arcane.iris.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ServerConfiguratorDatapackFingerprintTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Method fingerprintMethod() throws Exception {
        return ServerConfigurator.class.getMethod("computePackFingerprint", File.class);
    }

    @Test
    public void computePackFingerprintReturnsSameHashForUnchangedFiles() throws Exception {
        Method method = fingerprintMethod();
        File packsDir = tmp.newFolder("packs");
        File dimFile = new File(packsDir, "testpack/dimensions/overworld.json");
        dimFile.getParentFile().mkdirs();
        dimFile.createNewFile();

        String fp1 = (String) method.invoke(null, packsDir);
        String fp2 = (String) method.invoke(null, packsDir);

        assertNotNull("Fingerprint must not be null", fp1);
        assertEquals("Same unchanged files must produce identical fingerprint", fp1, fp2);
    }

    @Test
    public void computePackFingerprintIgnoresMetadataOnlyChanges() throws Exception {
        Method method = fingerprintMethod();
        File packsDir = tmp.newFolder("packs");
        File dimFile = new File(packsDir, "testpack/dimensions/overworld.json");
        dimFile.getParentFile().mkdirs();
        dimFile.createNewFile();

        String fp1 = (String) method.invoke(null, packsDir);
        dimFile.setLastModified(dimFile.lastModified() + 2000L);
        String fp2 = (String) method.invoke(null, packsDir);

        assertEquals("Metadata-only changes must not alter a content fingerprint", fp1, fp2);
    }

    @Test
    public void computePackFingerprintDetectsEqualSizeContentWithRestoredMtime() throws Exception {
        File packsDir = tmp.newFolder("content-packs");
        Path dimension = packsDir.toPath().resolve("testpack/dimensions/overworld.json");
        Files.createDirectories(dimension.getParent());
        Files.writeString(dimension, "aaaa", StandardCharsets.UTF_8);
        FileTime originalMtime = Files.getLastModifiedTime(dimension);
        String before = ServerConfigurator.computePackFingerprint(packsDir);

        Files.writeString(dimension, "bbbb", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(dimension, originalMtime);
        String after = ServerConfigurator.computePackFingerprint(packsDir);

        assertNotEquals("Equal-size content changes must alter the fingerprint", before, after);
    }

    @Test
    public void computePackFingerprintChangesWhenFileIsAdded() throws Exception {
        Method method = fingerprintMethod();
        File packsDir = tmp.newFolder("packs");
        File dimDir = new File(packsDir, "testpack/dimensions");
        dimDir.mkdirs();
        File dimFile = new File(dimDir, "overworld.json");
        dimFile.createNewFile();

        String fp1 = (String) method.invoke(null, packsDir);
        File extraFile = new File(dimDir, "nether.json");
        extraFile.createNewFile();
        String fp2 = (String) method.invoke(null, packsDir);

        assertNotEquals("Adding a file must produce a different fingerprint", fp1, fp2);
    }

    @Test
    public void computePackFingerprintExcludesHiddenTransactionStages() throws Exception {
        File packsDir = tmp.newFolder("hidden-packs");
        Path visible = packsDir.toPath().resolve("testpack/dimensions/overworld.json");
        Path hidden = packsDir.toPath().resolve(".iris-import-123/dimensions/overworld.json");
        Files.createDirectories(visible.getParent());
        Files.createDirectories(hidden.getParent());
        Files.writeString(visible, "visible", StandardCharsets.UTF_8);
        Files.writeString(hidden, "stage-one", StandardCharsets.UTF_8);
        String before = ServerConfigurator.computePackFingerprint(packsDir);

        Files.writeString(hidden, "stage-two", StandardCharsets.UTF_8);

        assertEquals(before, ServerConfigurator.computePackFingerprint(packsDir));
    }

    @Test
    public void computePackFingerprintIgnoresGeneratedCodeWorkspaceFiles() throws Exception {
        File packsDir = tmp.newFolder("workspace-packs");
        Path dimension = packsDir.toPath().resolve("overworld/dimensions/overworld.json");
        Files.createDirectories(dimension.getParent());
        Files.writeString(dimension, "authored", StandardCharsets.UTF_8);
        String before = ServerConfigurator.computePackFingerprint(packsDir);

        Path workspace = packsDir.toPath().resolve("overworld/overworld.code-workspace");
        Files.writeString(workspace, "{\"folders\":[]}", StandardCharsets.UTF_8);

        assertEquals("Iris-generated workspace files must not alter the fingerprint",
                before, ServerConfigurator.computePackFingerprint(packsDir));

        Files.writeString(workspace, "{\"folders\":[{\"path\":\".\"}]}", StandardCharsets.UTF_8);

        assertEquals("Reordered workspace bytes must not alter the fingerprint",
                before, ServerConfigurator.computePackFingerprint(packsDir));
    }

    @Test
    public void resolvePackFingerprintReusesCachedContentWhileMetadataIsUnchanged() throws Exception {
        File packsDir = tmp.newFolder("two-tier-packs");
        Path dimension = packsDir.toPath().resolve("testpack/dimensions/overworld.json");
        Files.createDirectories(dimension.getParent());
        Files.writeString(dimension, "aaaa", StandardCharsets.UTF_8);
        ServerConfigurator.PackFingerprint first =
                ServerConfigurator.resolvePackFingerprint(packsDir, "", "");
        assertEquals(ServerConfigurator.computePackFingerprint(packsDir), first.content());
        assertNotEquals("", first.metadata());

        FileTime originalMtime = Files.getLastModifiedTime(dimension);
        Files.writeString(dimension, "bbbb", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(dimension, originalMtime);
        ServerConfigurator.PackFingerprint reused =
                ServerConfigurator.resolvePackFingerprint(packsDir, first.metadata(), first.content());

        assertEquals("Unchanged metadata must reuse the cached content fingerprint",
                first.content(), reused.content());

        Files.setLastModifiedTime(dimension, FileTime.fromMillis(originalMtime.toMillis() + 5000L));
        ServerConfigurator.PackFingerprint rehashed =
                ServerConfigurator.resolvePackFingerprint(packsDir, first.metadata(), first.content());

        assertNotEquals("Changed metadata must re-hash pack contents",
                first.content(), rehashed.content());
        assertEquals(ServerConfigurator.computePackFingerprint(packsDir), rehashed.content());
    }

    @Test
    public void computePackMetadataDigestIgnoresGeneratedCodeWorkspaceFiles() throws Exception {
        File packsDir = tmp.newFolder("metadata-workspace-packs");
        Path dimension = packsDir.toPath().resolve("overworld/dimensions/overworld.json");
        Files.createDirectories(dimension.getParent());
        Files.writeString(dimension, "authored", StandardCharsets.UTF_8);
        String before = ServerConfigurator.computePackMetadataDigest(packsDir);

        Files.writeString(packsDir.toPath().resolve("overworld/overworld.code-workspace"),
                "{\"folders\":[]}", StandardCharsets.UTF_8);

        assertEquals(before, ServerConfigurator.computePackMetadataDigest(packsDir));
    }

    @Test
    public void computePackFingerprintRejectsSymbolicLinks() throws Exception {
        File packsDir = tmp.newFolder("unsafe-packs");
        Path pack = packsDir.toPath().resolve("testpack");
        Path outside = tmp.newFile("outside.json").toPath();
        Files.createDirectories(pack);
        Path link = pack.resolve("linked.json");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assume.assumeNoException(exception);
        }

        try {
            ServerConfigurator.computePackFingerprint(packsDir);
            fail("Symbolic links must be rejected");
        } catch (UncheckedIOException expected) {
            assertTrue(expected.getMessage().contains("fingerprint"));
        }
    }

    @Test
    public void computePackFingerprintReadsSafeSymbolicPackRoots() throws Exception {
        File packsDir = tmp.newFolder("linked-root-packs");
        Path externalPack = tmp.newFolder("linked-pack").toPath();
        Path dimension = externalPack.resolve("dimensions/overworld.json");
        Files.createDirectories(dimension.getParent());
        Files.writeString(dimension, "first", StandardCharsets.UTF_8);
        Path link = packsDir.toPath().resolve("overworld");
        try {
            Files.createSymbolicLink(link, externalPack);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assume.assumeNoException(exception);
        }
        String before = ServerConfigurator.computePackFingerprint(packsDir);

        Files.writeString(dimension, "other", StandardCharsets.UTF_8);

        assertNotEquals(before, ServerConfigurator.computePackFingerprint(packsDir));
    }

    @Test
    public void computePackFingerprintReadsSafeSymbolicWorkspaceRoots() throws Exception {
        Path workspace = tmp.newFolder("pack-workspace").toPath();
        Path externalPack = tmp.newFolder("workspace-linked-pack").toPath();
        Path dimension = externalPack.resolve("dimensions/overworld.json");
        Files.createDirectories(dimension.getParent());
        Files.writeString(dimension, "first", StandardCharsets.UTF_8);
        Path packLink = workspace.resolve("overworld");
        Path workspaceLink = tmp.getRoot().toPath().resolve("packs-link");
        try {
            Files.createSymbolicLink(packLink, externalPack);
            Files.createSymbolicLink(workspaceLink, workspace);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assume.assumeNoException(exception);
        }
        String before = ServerConfigurator.computePackFingerprint(workspaceLink.toFile());
        assertEquals(ServerConfigurator.computePackFingerprint(workspace.toFile()), before);

        Files.writeString(dimension, "other", StandardCharsets.UTF_8);

        assertNotEquals(before, ServerConfigurator.computePackFingerprint(workspaceLink.toFile()));
    }

    @Test
    public void computePackFingerprintRejectsDanglingSymbolicWorkspaceRoots() throws Exception {
        Path workspaceLink = tmp.getRoot().toPath().resolve("dangling-packs-link");
        try {
            Files.createSymbolicLink(workspaceLink, tmp.getRoot().toPath().resolve("missing-workspace"));
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assume.assumeNoException(exception);
        }

        try {
            ServerConfigurator.computePackFingerprint(workspaceLink.toFile());
            fail("Dangling symbolic workspace roots must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing or unsafe"));
        }
    }

    @Test
    public void incompleteExternalDatapackRecoveryBlocksCompilation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/ServerConfigurator.java"));
        int recovery = source.indexOf("if (!DatapackIngestService.reapplyFromStaging(datapacksFolders))");
        int blocked = source.indexOf("return DatapackInstallResult.failedResult();", recovery);
        int compile = source.indexOf("IrisDatapackCompiler.compile(", recovery);

        assertTrue(recovery >= 0);
        assertTrue(blocked > recovery);
        assertTrue(compile > blocked);
    }
}
