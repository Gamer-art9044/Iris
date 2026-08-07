import org.gradle.api.GradleException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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

public class BukkitArtifactVerifierTest {
    private static final String PLUGIN_DESCRIPTOR = "plugin.yml";
    private static final String NMS_BINDING = "art/arcane/iris/core/nms/v26_2_R1/NMSBinding";
    private static final List<String> REQUIRED_ENTRIES = List.of(PLUGIN_DESCRIPTOR, NMS_BINDING + ".class");
    private static final int LOCALES = 2;
    private static final int CAFFEINE_FACTORIES = 2;
    private static final int MATTER_SLICES = 1;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsCompleteArtifact() throws Exception {
        File artifact = createArtifact(validEntries());

        BukkitArtifactVerifier.verify(artifact, REQUIRED_ENTRIES, LOCALES, CAFFEINE_FACTORIES, MATTER_SLICES);
    }

    @Test
    public void rejectsMissingRequiredEntry() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.remove(PLUGIN_DESCRIPTOR);
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> BukkitArtifactVerifier.verify(artifact, REQUIRED_ENTRIES, LOCALES, CAFFEINE_FACTORIES,
                        MATTER_SLICES));
        assertTrue(failure.getMessage().contains(PLUGIN_DESCRIPTOR));
    }

    @Test
    public void rejectsExcludedClassThatIsStillReferenced() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.remove("art/arcane/volmlib/util/noise/CNG.class");
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> BukkitArtifactVerifier.verify(artifact, REQUIRED_ENTRIES, LOCALES, CAFFEINE_FACTORIES,
                        MATTER_SLICES));
        assertTrue(failure.getMessage().contains("art/arcane/volmlib/util/noise/CNG"));
    }

    @Test
    public void acceptsReferenceToRuntimeDownloadedLibrary() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.put("art/arcane/iris/Consumer.class",
                classReferencing("art/arcane/iris/Consumer", "art/arcane/iris/util/kyori/adventure/text/Component"));
        File artifact = createArtifact(entries);

        BukkitArtifactVerifier.verify(artifact, REQUIRED_ENTRIES, LOCALES, CAFFEINE_FACTORIES, MATTER_SLICES);
    }

    @Test
    public void rejectsStrippedCaffeineFactories() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.remove("art/arcane/iris/util/caffeine/cache/SSMS.class");
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> BukkitArtifactVerifier.verify(artifact, REQUIRED_ENTRIES, LOCALES, CAFFEINE_FACTORIES,
                        MATTER_SLICES));
        assertTrue(failure.getMessage().contains("generated Caffeine cache classes"));
    }

    @Test
    public void rejectsDroppedLocale() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.remove("languages/de_DE.json");
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> BukkitArtifactVerifier.verify(artifact, REQUIRED_ENTRIES, LOCALES, CAFFEINE_FACTORIES,
                        MATTER_SLICES));
        assertTrue(failure.getMessage().contains("locale files"));
    }

    @Test
    public void rejectsDroppedMatterSlice() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.remove("art/arcane/volmlib/util/matter/slices/BlockMatter.class");
        File artifact = createArtifact(entries);

        GradleException failure = assertThrows(GradleException.class,
                () -> BukkitArtifactVerifier.verify(artifact, REQUIRED_ENTRIES, LOCALES, CAFFEINE_FACTORIES,
                        MATTER_SLICES));
        assertTrue(failure.getMessage().contains("Matter slice types"));
    }

    @Test
    public void ignoresAnnotationOnlyReferences() throws Exception {
        Map<String, byte[]> entries = validEntries();
        entries.put("art/arcane/iris/Annotated.class",
                classAnnotatedWith("art/arcane/iris/Annotated", "com/google/errorprone/annotations/CanIgnoreReturnValue"));
        File artifact = createArtifact(entries);

        BukkitArtifactVerifier.verify(artifact, REQUIRED_ENTRIES, LOCALES, CAFFEINE_FACTORIES, MATTER_SLICES);
    }

    private Map<String, byte[]> validEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PLUGIN_DESCRIPTOR, "name: Iris\n".getBytes(StandardCharsets.UTF_8));
        entries.put("languages/de_DE.json", "{}".getBytes(StandardCharsets.UTF_8));
        entries.put("languages/fr_FR.json", "{}".getBytes(StandardCharsets.UTF_8));
        entries.put(NMS_BINDING + ".class",
                classReferencing(NMS_BINDING, "art/arcane/volmlib/util/noise/CNG"));
        entries.put("art/arcane/volmlib/util/noise/CNG.class", emptyClass("art/arcane/volmlib/util/noise/CNG"));
        entries.put("art/arcane/volmlib/util/matter/slices/BlockMatter.class",
                emptyClass("art/arcane/volmlib/util/matter/slices/BlockMatter"));
        entries.put("art/arcane/iris/util/caffeine/cache/SSMS.class",
                emptyClass("art/arcane/iris/util/caffeine/cache/SSMS"));
        entries.put("art/arcane/iris/util/caffeine/cache/SSLMS.class",
                emptyClass("art/arcane/iris/util/caffeine/cache/SSLMS"));
        return entries;
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classReferencing(String internalName, String referenced) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, referenced);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classAnnotatedWith(String internalName, String annotation) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitAnnotation('L' + annotation + ';', true).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private File createArtifact(Map<String, byte[]> entries) throws Exception {
        File artifact = temporaryFolder.newFile("Iris-bukkit-" + System.nanoTime() + ".jar");
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(artifact))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return artifact;
    }
}
