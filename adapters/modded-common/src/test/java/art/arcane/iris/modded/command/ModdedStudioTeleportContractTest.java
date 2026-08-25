package art.arcane.iris.modded.command;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedStudioTeleportContractTest {
    private static final String SOURCE_ROOT_PROPERTY = "iris.moddedCommonSources";

    @Test
    public void studioOpenSerializesReplacementAndWaitsForTheNativePath() throws IOException {
        String source = source("command/ModdedStudioCommands.java");
        String open = method(source, "private static int open(");
        String execute = method(source, "private static void executeStudioOpen(");

        assertTrue(open.contains("TRANSITIONS.submit("));
        assertFalse(open.contains("orTimeout("));
        assertFalse(open.contains("deadlineNanos"));
        assertBefore(execute, "replaceExistingStudio(", "ModdedDimensionManager.create(");
        assertBefore(execute, "ModdedDimensionManager.create(", "ModdedDimensionManager.teleportAsync(");
        assertFalse(execute.contains("deadlineNanos"));
        assertFalse(source.contains("player.teleportTo(studio"));
    }

    @Test
    public void studioTeleportAndVisionShareWarmFutureSemantics() throws IOException {
        String studio = source("command/ModdedStudioCommands.java");
        String vision = source("command/ModdedVisionOverlay.java");
        String dimensions = source("ModdedDimensionManager.java");

        assertTrue(studio.contains("private static CompletableFuture<Void> teleportToStudio("));
        assertTrue(studio.contains("ModdedDimensionManager.teleportAsync("));
        assertTrue(dimensions.contains("TELEPORT_WARM_RADIUS = 0"));
        assertTrue(dimensions.contains("addTicketAndLoadWithRadius(\n"
                + "                    TELEPORT_WARM_TICKET,\n"
                + "                    chunkPos,\n"
                + "                    TELEPORT_WARM_RADIUS)"));
        assertTrue(dimensions.contains("removeTicketWithRadius(\n"
                + "                    TELEPORT_WARM_TICKET,\n"
                + "                    chunkPos,\n"
                + "                    TELEPORT_WARM_RADIUS)"));
        assertTrue(vision.contains("Math.floor(worldX)"));
        assertTrue(vision.contains("Math.floor(worldZ)"));
        assertTrue(vision.contains("if (opener != null)"));
        assertTrue(vision.contains("ModdedDimensionManager.teleportAsync("));
        assertFalse(vision.contains("level.getHeight("));
        assertFalse(vision.contains("player.teleportTo("));
    }

    private static String source(String relative) throws IOException {
        String root = System.getProperty(SOURCE_ROOT_PROPERTY);
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("Missing test property " + SOURCE_ROOT_PROPERTY);
        }
        return Files.readString(Path.of(root).resolve("art/arcane/iris/modded").resolve(relative))
                .replace("\r\n", "\n");
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
