package art.arcane.iris.core;

import art.arcane.iris.core.lifecycle.BukkitWorldConfiguration.IrisGeneratorBinding;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IrisDatapackCompilerInputFingerprintTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void compilerInputsIncludeDimensionsBiomesAndSnippets() throws Exception {
        Path pack = activePack("compiler-inputs");
        Path biome = write(pack.resolve("biomes/plains.json"), "biome-a");
        Path snippet = write(pack.resolve("snippet/dimension/options.json"), "snippet-a");
        String initial = fingerprint(pack, false, "compiler-a");

        Files.writeString(biome, "biome-b", StandardCharsets.UTF_8);
        String biomeChanged = fingerprint(pack, false, "compiler-a");
        Files.writeString(snippet, "snippet-b", StandardCharsets.UTF_8);
        String snippetChanged = fingerprint(pack, false, "compiler-a");
        Files.writeString(pack.resolve("dimensions/overworld.json"), "dimension-b", StandardCharsets.UTF_8);
        String dimensionChanged = fingerprint(pack, false, "compiler-a");

        assertNotEquals(initial, biomeChanged);
        assertNotEquals(biomeChanged, snippetChanged);
        assertNotEquals(snippetChanged, dimensionChanged);
    }

    @Test
    public void compilerInputsExcludeObjectsAndJigsawResources() throws Exception {
        Path pack = activePack("non-compiler-inputs");
        Path object = write(pack.resolve("objects/house.iob"), "object-a");
        Path structure = write(pack.resolve("structures/village.json"), "structure-a");
        Path piece = write(pack.resolve("jigsaw-pieces/road.json"), "piece-a");
        String initial = fingerprint(pack, false, "compiler-a");

        Files.writeString(object, "object-b", StandardCharsets.UTF_8);
        Files.writeString(structure, "structure-b", StandardCharsets.UTF_8);
        Files.writeString(piece, "piece-b", StandardCharsets.UTF_8);

        assertEquals(initial, fingerprint(pack, false, "compiler-a"));
    }

    @Test
    public void contentHashDetectsEqualSizeEditsWithRestoredMtime() throws Exception {
        Path pack = activePack("restored-mtime");
        Path dimension = pack.resolve("dimensions/overworld.json");
        Files.writeString(dimension, "aaaa", StandardCharsets.UTF_8);
        FileTime originalMtime = Files.getLastModifiedTime(dimension);
        String initial = fingerprint(pack, false, "compiler-a");

        Files.writeString(dimension, "bbbb", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(dimension, originalMtime);

        assertNotEquals(initial, fingerprint(pack, false, "compiler-a"));
    }

    @Test
    public void outputPolicyAndCompilerIdentitySaltTheFingerprint() throws Exception {
        Path pack = activePack("compiler-salt");
        String initial = fingerprint(pack, false, "compiler-a");

        assertNotEquals(initial, fingerprint(pack, true, "compiler-a"));
        assertNotEquals(initial, fingerprint(pack, false, "compiler-b"));
    }

    @Test
    public void configuredLevelStemBindingsSaltTheFingerprint() throws Exception {
        Path pack = activePack("binding-inputs");
        IrisGeneratorBinding moon = binding("moon", "overworld");
        IrisGeneratorBinding mars = binding("mars", "overworld");
        String empty = fingerprint(pack, List.of(), false, "compiler-a");
        String moonOnly = fingerprint(pack, List.of(moon), false, "compiler-a");
        String both = fingerprint(pack, List.of(mars, moon), false, "compiler-a");

        assertNotEquals(empty, moonOnly);
        assertNotEquals(moonOnly, both);
        assertEquals(
                both,
                fingerprint(pack, List.of(moon, mars), false, "compiler-a")
        );
    }

    @Test
    public void compilerInputsRejectNestedSymbolicLinks() throws Exception {
        Path pack = activePack("unsafe-input");
        Path outside = tmp.newFile("outside.json").toPath();
        Path link = pack.resolve("biomes/linked.json");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assume.assumeNoException(exception);
        }

        try {
            fingerprint(pack, false, "compiler-a");
            fail("Compiler-input symbolic links must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("symbolic link"));
        }
    }

    @Test
    public void inputRootDiscoveryDoesNotTraverseUnrelatedPackTrees() throws Exception {
        Path dataDirectory = tmp.newFolder("input-root-data").toPath();
        Path serverRoot = tmp.newFolder("input-root-server").toPath();
        Path pack = dataDirectory.resolve("packs/example");
        write(pack.resolve("dimensions/example.json"), "{}");
        Path unrelated = tmp.newFile("unrelated.json").toPath();
        Path link = pack.resolve("objects/unsafe-link.json");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, unrelated);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assume.assumeNoException(exception);
        }

        List<File> compilerRoots = IrisDatapackCompiler.collectCompilerInputRoots(dataDirectory, serverRoot);

        assertEquals(List.of(pack.toAbsolutePath().normalize().toFile()), compilerRoots);
        try {
            IrisDatapackCompiler.collectPackRoots(dataDirectory, serverRoot);
            fail("Whole-pack discovery must continue rejecting unrelated symbolic links");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("symbolic link"));
        }
    }

    @Test
    public void inputRootDiscoveryAcceptsTopLevelWorkspaceSymbolicLinks() throws Exception {
        Path dataDirectory = tmp.newFolder("linked-root-data").toPath();
        Path serverRoot = tmp.newFolder("linked-root-server").toPath();
        Path realPack = tmp.newFolder("linked-root-pack").toPath();
        write(realPack.resolve("dimensions/example.json"), "{}");
        Path linkedPack = dataDirectory.resolve("packs/example");
        Files.createDirectories(linkedPack.getParent());
        try {
            Files.createSymbolicLink(linkedPack, realPack);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assume.assumeNoException(exception);
        }

        List<File> compilerRoots = IrisDatapackCompiler.collectCompilerInputRoots(dataDirectory, serverRoot);
        List<File> compilationRoots = IrisDatapackCompiler.collectPackRoots(dataDirectory, serverRoot);

        assertEquals(List.of(linkedPack.toAbsolutePath().normalize().toFile()), compilerRoots);
        assertEquals(compilerRoots, compilationRoots);
        assertTrue(!IrisDatapackCompiler.computeInputFingerprint(
                compilerRoots,
                List.of(),
                false,
                "compiler-a").isBlank());
    }

    private Path activePack(String name) throws IOException {
        Path pack = tmp.newFolder(name).toPath();
        write(pack.resolve("dimensions/overworld.json"), "dimension-a");
        return pack;
    }

    private Path write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private String fingerprint(Path pack, boolean adjustVanillaHeight, String compilerIdentity) throws IOException {
        return fingerprint(pack, List.of(), adjustVanillaHeight, compilerIdentity);
    }

    private String fingerprint(
            Path pack,
            List<IrisGeneratorBinding> bindings,
            boolean adjustVanillaHeight,
            String compilerIdentity
    ) throws IOException {
        return IrisDatapackCompiler.computeInputFingerprint(
                List.of(pack.toFile()),
                bindings,
                adjustVanillaHeight,
                compilerIdentity);
    }

    private IrisGeneratorBinding binding(String worldKey, String dimension) {
        return new IrisGeneratorBinding(
                "world_iris_" + worldKey,
                new WorldSlotKey("iris", worldKey),
                dimension
        );
    }
}
