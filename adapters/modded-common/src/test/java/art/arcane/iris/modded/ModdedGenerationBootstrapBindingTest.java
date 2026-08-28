package art.arcane.iris.modded;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ModdedGenerationBootstrapBindingTest {
    private static final String SOURCE_ROOT_PROPERTY = "iris.moddedCommonSources";

    @Test
    public void publishedLevelRequiresCurrentGeneratorIdentity() {
        IrisModdedChunkGenerator generator = mock(IrisModdedChunkGenerator.class, CALLS_REAL_METHODS);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getChunkSource()).thenReturn(chunkSource);
        when(chunkSource.getGenerator()).thenReturn(generator);

        assertSame(level, generator.requirePublishedLevel(server, Level.OVERWORLD));

        ChunkGenerator otherGenerator = mock(ChunkGenerator.class);
        when(chunkSource.getGenerator()).thenReturn(otherGenerator);
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> generator.requirePublishedLevel(server, Level.OVERWORLD));
        assertTrue(mismatch.getMessage().contains("does not use Iris generator"));
    }

    @Test
    public void publishedLevelRejectsMissingServerAndWorld() {
        IrisModdedChunkGenerator generator = mock(IrisModdedChunkGenerator.class, CALLS_REAL_METHODS);
        IllegalStateException missingServer = assertThrows(IllegalStateException.class,
                () -> generator.requirePublishedLevel(null, Level.OVERWORLD));
        assertTrue(missingServer.getMessage().contains("server is unavailable"));

        MinecraftServer server = mock(MinecraftServer.class);
        IllegalStateException missingWorld = assertThrows(IllegalStateException.class,
                () -> generator.requirePublishedLevel(server, Level.OVERWORLD));
        assertTrue(missingWorld.getMessage().contains("no published ServerLevel"));
    }

    @Test
    public void staleSnapshotResolvesOnlyCanonicalOverworldOwnedByGenerator() {
        IrisModdedChunkGenerator generator = mock(IrisModdedChunkGenerator.class, CALLS_REAL_METHODS);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel overworld = mock(ServerLevel.class);
        ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(overworld);
        when(overworld.getChunkSource()).thenReturn(chunkSource);
        when(chunkSource.getGenerator()).thenReturn(generator);

        assertSame(overworld, generator.resolveBoundLevel(server, List.of()));

        ChunkGenerator otherGenerator = mock(ChunkGenerator.class);
        when(chunkSource.getGenerator()).thenReturn(otherGenerator);
        assertNull(generator.resolveBoundLevel(server, List.of()));
        verify(server, never()).getLevel(Level.NETHER);
    }

    @Test
    public void dynamicLevelStillResolvesFromSnapshotWithoutCanonicalLookup() {
        IrisModdedChunkGenerator generator = mock(IrisModdedChunkGenerator.class, CALLS_REAL_METHODS);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel dynamicLevel = mock(ServerLevel.class);
        ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        when(dynamicLevel.getChunkSource()).thenReturn(chunkSource);
        when(chunkSource.getGenerator()).thenReturn(generator);

        assertSame(dynamicLevel, generator.resolveBoundLevel(server, List.of(dynamicLevel)));
        verify(server, never()).getLevel(Level.OVERWORLD);
    }

    @Test
    public void firstGenerationStagesProvideAuthoritativeLevelContext() throws IOException {
        String source = source("IrisModdedChunkGenerator.java");
        String structures = method(source, "public void createStructures(");
        String references = method(source, "public void createReferences(");
        assertTrue(structures.contains("Engine current = engine(levelKey);"));
        assertTrue(references.contains("Engine current = engine(level.getLevel());"));

        String publishedBinding = method(source, "private Engine engine(ResourceKey<Level> levelKey)");
        assertTrue(publishedBinding.contains("requirePublishedLevel(ModdedEngineBootstrap.currentServer(), levelKey)"));
        assertTrue(publishedBinding.contains("return bindGenerationLevel(level);"));
        assertFalse(publishedBinding.contains("ModdedServerLevels.level("));

        String fullBinding = method(source, "private Engine bindGenerationLevel(ServerLevel level)");
        assertTrue(fullBinding.contains("bindLevel(level);"));
        assertTrue(fullBinding.contains("Engine bound = readyEngine();"));

        String genericBinding = method(source, "Engine engine()");
        assertTrue(genericBinding.contains("return bindGenerationLevel(level);"));
    }

    @Test
    public void constructorMixinIsNotPartOfGenerationBootstrap() throws IOException {
        String sourceRoot = System.getProperty(SOURCE_ROOT_PROPERTY);
        assertTrue(sourceRoot != null && !sourceRoot.isBlank());
        Path root = Path.of(sourceRoot);
        assertFalse(Files.exists(root.resolve(Path.of("art", "arcane", "iris", "modded", "mixin",
                "ServerLevelBindingMixin.java"))));
        String mixinConfig = Files.readString(root.getParent().resolve("resources/irisworldgen.entity.mixins.json"));
        assertFalse(mixinConfig.contains("ServerLevelBindingMixin"));
    }

    private String source(String fileName) throws IOException {
        String sourceRoot = System.getProperty(SOURCE_ROOT_PROPERTY);
        assertTrue(sourceRoot != null && !sourceRoot.isBlank());
        return Files.readString(Path.of(sourceRoot, "art", "arcane", "iris", "modded", fileName));
    }

    private String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0);
        int body = source.indexOf('{', start);
        assertTrue(body >= 0);
        int depth = 0;
        for (int index = body; index < source.length(); index++) {
            char token = source.charAt(index);
            if (token == '{') {
                depth++;
            } else if (token == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new IllegalArgumentException("Unclosed source contract method: " + signature);
    }
}
