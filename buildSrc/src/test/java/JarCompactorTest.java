import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class JarCompactorTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void preservesFilesAndOmitsDirectoryEntries() throws Exception {
        File artifact = temporaryFolder.newFile("artifact.jar");
        byte[] content = "Iris artifact content".getBytes(StandardCharsets.UTF_8);
        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(artifact))) {
            output.putNextEntry(new JarEntry("example/"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("example/value.txt"));
            output.write(content);
            output.closeEntry();
        }

        JarCompactor.compact(artifact);

        try (JarFile jar = new JarFile(artifact)) {
            assertNull(jar.getJarEntry("example/"));
            assertArrayEquals(content, jar.getInputStream(
                    jar.getJarEntry("example/value.txt")).readAllBytes());
        }
    }
}
