import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

public final class ModdedArtifactVerifier {
    private static final String CODEC_CLASS = "art/arcane/volmlib/util/mantle/io/Lz4IOWorkerCodecSupport.class";
    private static final List<String> INTERNAL_LZ4_REFERENCES = List.of(
            "net/jpountz/lz4/LZ4BlockInputStream",
            "net/jpountz/lz4/LZ4BlockOutputStream"
    );
    private static final String INTERNAL_OSHI_REFERENCE = "oshi/SystemInfo";
    private static final List<String> BUNDLED_RUNTIME_PREFIXES = List.of(
            "net/jpountz/",
            "oshi/",
            "com/sun/jna/",
            "art/arcane/iris/shadow/jpountz/",
            "art/arcane/iris/shadow/oshi/",
            "art/arcane/iris/shadow/jna/"
    );
    private static final List<String> PRIVATE_REFERENCE_PREFIXES = List.of(
            "art/arcane/iris/shadow/jpountz",
            "art/arcane/iris/shadow/oshi",
            "art/arcane/iris/shadow/jna"
    );

    private ModdedArtifactVerifier() {
    }

    public static void verify(File artifact, List<String> requiredEntries) {
        if (!artifact.isFile()) {
            throw new GradleException("Missing modded Iris artifact: " + artifact.getAbsolutePath());
        }

        try (JarFile jar = new JarFile(artifact)) {
            for (String requiredEntry : requiredEntries) {
                if (jar.getJarEntry(requiredEntry) == null) {
                    throw new GradleException(artifact.getName() + " is missing " + requiredEntry);
                }
            }

            Set<String> bundledRuntimeEntries = new LinkedHashSet<>();
            Set<String> privateReferenceClasses = new LinkedHashSet<>();
            Set<String> codecReferences = new LinkedHashSet<>();
            boolean oshiReference = false;
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                if (startsWithAny(name, BUNDLED_RUNTIME_PREFIXES)) {
                    bundledRuntimeEntries.add(name);
                }
                if (isNestedJar(name)) {
                    inspectNestedJar(jar, entry, bundledRuntimeEntries, privateReferenceClasses);
                }
                if (!name.endsWith(".class")) {
                    continue;
                }

                String bytecode = readClass(jar, entry);
                if (containsAny(bytecode, PRIVATE_REFERENCE_PREFIXES)) {
                    privateReferenceClasses.add(name);
                }
                if (bytecode.contains(INTERNAL_OSHI_REFERENCE)) {
                    oshiReference = true;
                }
                if (name.equals(CODEC_CLASS)) {
                    for (String reference : INTERNAL_LZ4_REFERENCES) {
                        if (bytecode.contains(reference)) {
                            codecReferences.add(reference);
                        }
                    }
                }
            }

            if (!bundledRuntimeEntries.isEmpty()) {
                throw new GradleException(artifact.getName() + " duplicates Minecraft runtime libraries: "
                        + preview(bundledRuntimeEntries));
            }
            if (!privateReferenceClasses.isEmpty()) {
                throw new GradleException(artifact.getName() + " contains unresolved Iris-private runtime references in: "
                        + preview(privateReferenceClasses));
            }
            if (!codecReferences.containsAll(INTERNAL_LZ4_REFERENCES)) {
                throw new GradleException(artifact.getName() + " does not link region storage to Minecraft's LZ4 runtime");
            }
            if (!oshiReference) {
                throw new GradleException(artifact.getName() + " does not link hardware diagnostics to Minecraft's OSHI runtime");
            }
        } catch (IOException e) {
            throw new GradleException("Unable to verify modded Iris artifact " + artifact.getAbsolutePath(), e);
        }
    }

    private static String readClass(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, List<String> fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static void inspectNestedJar(JarFile jar, JarEntry nestedEntry,
                                         Set<String> bundledRuntimeEntries,
                                         Set<String> privateReferenceClasses) throws IOException {
        String nestedName = nestedEntry.getName();
        if (isNamedRuntimeLibrary(nestedName)) {
            bundledRuntimeEntries.add(nestedName);
        }

        try (InputStream input = jar.getInputStream(nestedEntry);
             JarInputStream nestedJar = new JarInputStream(input)) {
            JarEntry entry;
            while ((entry = nestedJar.getNextJarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName();
                String path = nestedName + "!/" + name;
                if (startsWithAny(name, BUNDLED_RUNTIME_PREFIXES)) {
                    bundledRuntimeEntries.add(path);
                }
                if (name.endsWith(".class")) {
                    String bytecode = new String(nestedJar.readAllBytes(), StandardCharsets.ISO_8859_1);
                    if (containsAny(bytecode, PRIVATE_REFERENCE_PREFIXES)) {
                        privateReferenceClasses.add(path);
                    }
                }
            }
        }
    }

    private static boolean isNestedJar(String name) {
        return name.endsWith(".jar")
                && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"));
    }

    private static boolean isNamedRuntimeLibrary(String name) {
        if (!isNestedJar(name)) {
            return false;
        }

        String fileName = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return fileName.startsWith("lz4") || fileName.startsWith("oshi") || fileName.startsWith("jna");
    }

    private static String preview(Set<String> values) {
        return String.join(", ", values.stream().limit(8).toList());
    }
}
