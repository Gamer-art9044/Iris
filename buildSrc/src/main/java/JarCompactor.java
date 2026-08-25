import org.gradle.api.GradleException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class JarCompactor {
    private static final int BUFFER_BYTES = 64 * 1024;

    private JarCompactor() {
    }

    public static void compact(File artifact) {
        if (artifact == null || !artifact.isFile()) {
            throw new GradleException("Cannot compact missing jar artifact: " + artifact);
        }

        Path source = artifact.toPath();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(source.getParent(), artifact.getName(), ".compact");
            rewrite(source, temporary);
            replace(temporary, source);
        } catch (IOException exception) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw new GradleException("Unable to compact jar artifact " + artifact.getAbsolutePath(), exception);
        }
    }

    private static void rewrite(Path source, Path destination) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream rawInput = new BufferedInputStream(Files.newInputStream(source));
             ZipInputStream input = new ZipInputStream(rawInput);
             OutputStream rawOutput = new BufferedOutputStream(Files.newOutputStream(destination));
             ZipOutputStream output = new ZipOutputStream(rawOutput)) {
            output.setLevel(9);
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ZipEntry compacted = copyMetadata(entry);
                output.putNextEntry(compacted);
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        output.write(buffer, 0, read);
                    }
                }
                output.closeEntry();
            }
        }
    }

    private static ZipEntry copyMetadata(ZipEntry source) {
        ZipEntry target = new ZipEntry(source.getName());
        target.setMethod(ZipEntry.DEFLATED);
        if (source.getTime() >= 0L) {
            target.setTime(source.getTime());
        }
        if (source.getComment() != null) {
            target.setComment(source.getComment());
        }
        if (source.getExtra() != null) {
            target.setExtra(source.getExtra());
        }
        return target;
    }

    private static void replace(Path temporary, Path source) throws IOException {
        try {
            Files.move(
                    temporary,
                    source,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
