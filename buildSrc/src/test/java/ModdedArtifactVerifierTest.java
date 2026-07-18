import org.gradle.api.GradleException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModdedArtifactVerifierTest {
    private static final String METADATA = "fabric.mod.json";
    private static final String CODEC_CLASS = "art/arcane/volmlib/util/mantle/io/Lz4IOWorkerCodecSupport.class";
    private static final String HARDWARE_CLASS = "art/arcane/iris/util/common/misc/getHardware.class";
    private static final List<String> REQUIRED_ENTRIES = List.of(METADATA, CODEC_CLASS, HARDWARE_CLASS);
    private static final byte[] INTERNAL_CODEC = (
            "net/jpountz/lz4/LZ4BlockInputStream net/jpountz/lz4/LZ4BlockOutputStream"
    ).getBytes(StandardCharsets.ISO_8859_1);

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsMinecraftRuntimeReferences() throws Exception {
        Map<String, byte[]> entries = validEntries();
        File artifact = createArtifact(entries);

        ModdedArtifactVerifier.verify(artifact, REQUIRED_ENTRIES);
    }

    @Test
    public void rejectsRelocatedRuntimeReference() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.put("art/arcane/iris/Test.class",
                "art/arcane/iris/shadow/jpountz/lz4/LZ4BlockInputStream"
                        .getBytes(StandardCharsets.ISO_8859_1));
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> ModdedArtifactVerifier.verify(artifact, REQUIRED_ENTRIES));
        assertTrue(failure.getMessage().contains("Iris-private runtime references"));
    }

    @Test
    public void rejectsBundledMinecraftRuntimeLibrary() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.put("net/jpountz/lz4/LZ4BlockInputStream.class", new byte[]{0});
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> ModdedArtifactVerifier.verify(artifact, REQUIRED_ENTRIES));
        assertTrue(failure.getMessage().contains("duplicates Minecraft runtime libraries"));
    }

    @Test
    public void rejectsNestedMinecraftRuntimeLibrary() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.put("META-INF/jars/lz4-java.jar", new byte[]{0});
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> ModdedArtifactVerifier.verify(artifact, REQUIRED_ENTRIES));
        assertTrue(failure.getMessage().contains("duplicates Minecraft runtime libraries"));
    }

    @Test
    public void rejectsRenamedJarJarRuntimeLibrary() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.put("META-INF/jarjar/runtime-support.jar", createNestedArtifact(Map.of(
                "com/sun/jna/Native.class", new byte[]{0}
        )));
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> ModdedArtifactVerifier.verify(artifact, REQUIRED_ENTRIES));
        assertTrue(failure.getMessage().contains("duplicates Minecraft runtime libraries"));
    }

    @Test
    public void rejectsArtifactWithoutPlatformMetadata() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(CODEC_CLASS, INTERNAL_CODEC);
        entries.put(HARDWARE_CLASS, "oshi/SystemInfo".getBytes(StandardCharsets.ISO_8859_1));
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> ModdedArtifactVerifier.verify(artifact, REQUIRED_ENTRIES));
        assertTrue(failure.getMessage().contains("is missing " + METADATA));
    }

    private Map<String, byte[]> validEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(METADATA, new byte[]{0});
        entries.put(CODEC_CLASS, INTERNAL_CODEC);
        entries.put(HARDWARE_CLASS, "oshi/SystemInfo".getBytes(StandardCharsets.ISO_8859_1));
        return entries;
    }

    private File createArtifact(Map<String, byte[]> entries) throws Exception {
        File artifact = temporaryFolder.newFile("artifact-" + System.nanoTime() + ".jar");
        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(artifact))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return artifact;
    }

    private byte[] createNestedArtifact(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
