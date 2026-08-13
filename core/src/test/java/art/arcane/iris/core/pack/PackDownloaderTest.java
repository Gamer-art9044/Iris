/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.core.pack;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformStructureHooks;
import org.junit.Assume;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Answers;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PackDownloaderTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private IrisPlatform previousPlatform;
    private IrisSettings previousSettings;

    @Before
    public void bindPlatform() {
        previousPlatform = IrisPlatforms.isBound() ? IrisPlatforms.get() : null;
        previousSettings = IrisSettings.settings;
        IrisPlatforms.unbind();
        IrisPlatform platform = mock(IrisPlatform.class, Answers.CALLS_REAL_METHODS);
        PlatformStructureHooks structureHooks = mock(PlatformStructureHooks.class, Answers.CALLS_REAL_METHODS);
        when(platform.dataFolder()).thenReturn(temp.getRoot());
        when(platform.dataFile(any(String[].class))).thenAnswer(invocation -> {
            File file = temp.getRoot();
            for (Object argument : invocation.getArguments()) {
                file = new File(file, String.valueOf(argument));
            }
            return file;
        });
        when(platform.structureHooks()).thenReturn(structureHooks);
        when(structureHooks.structureKeys()).thenReturn(List.of("minecraft:village"));
        when(structureHooks.jigsawStructureKeys()).thenReturn(List.of("minecraft:village"));
        when(structureHooks.templatePoolKeys()).thenReturn(List.of("minecraft:empty"));
        IrisPlatforms.bind(platform);
        IrisSettings.settings = new IrisSettings();
    }

    @After
    public void restorePlatform() {
        IrisPlatforms.unbind();
        if (previousPlatform != null) {
            IrisPlatforms.bind(previousPlatform);
        }
        IrisSettings.settings = previousSettings;
        PackValidationRegistry.clear();
    }

    @Test
    public void resolvesDefaultOverworldBetaRelease() {
        assertEquals(
                "https://github.com/IrisDimensions/overworld/releases/download/beta/overworld.zip",
                PackDownloader.builtInPackUrl("overworld")
        );
        assertTrue(PackDownloader.isDefaultOverworld("overworld"));
        assertTrue(PackDownloader.isBuiltInPack("overworld"));
        assertEquals(List.of("overworld", "underworld"), PackDownloader.builtInPacks());
    }

    @Test
    public void resolvesUnderworldBetaRelease() {
        assertEquals(
                "https://github.com/IrisDimensions/underworld/releases/download/beta/underworld.zip",
                PackDownloader.builtInPackUrl("underworld")
        );
        assertTrue(PackDownloader.isBuiltInPack("underworld"));
    }

    @Test
    public void recognizesOnlyHttpZipUrlsAsDirectSources() {
        assertTrue(PackDownloader.isDirectZipUrl("https://packs.example.test/overworld.zip"));
        assertTrue(PackDownloader.isDirectZipUrl("http://127.0.0.1/pack.ZIP?token=value"));
        assertFalse(PackDownloader.isDirectZipUrl("https://packs.example.test/overworld.tar.gz"));
        assertFalse(PackDownloader.isDirectZipUrl("file:///tmp/overworld.zip"));
        assertFalse(PackDownloader.isDirectZipUrl("not-a-url"));
    }

    @Test
    public void directZipUrlInstallsSingleDimensionPackAndRequiresRestart() throws Exception {
        Path archive = temp.newFile("direct-pack.zip").toPath();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("README.md", "Direct pack");
        entries.put("wrapped/dimensions/direct_pack.json", "{\"name\":\"Direct\",\"regions\":[\"local\"],"
                + "\"structures\":[{\"nativeStructures\":[{\"structure\":\"test:future_structure\"}]}],"
                + "\"logicalHeight\":256,\"dimensionHeight\":{\"min\":-64,\"max\":320}}");
        entries.put("wrapped/dimensions/direct_pack_supporting.json", "{\"name\":\"Supporting\",\"regions\":[\"local\"],"
                + "\"logicalHeight\":256,\"dimensionHeight\":{\"min\":-64,\"max\":320}}");
        entries.put("wrapped/regions/local.json", "{\"name\":\"Local\",\"landBiomes\":[\"local\"]}");
        entries.put("wrapped/biomes/local.json", "{\"name\":\"Local\",\"derivative\":\"minecraft:plains\"}");
        writeArchive(archive, entries);
        byte[] response = Files.readAllBytes(archive);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/direct-pack.zip", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            File packsFolder = temp.newFolder("direct-url-packs");
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/direct-pack.zip";

            PackDownloader.PackInstallResult result = PackDownloader.downloadUrl(
                    packsFolder,
                    url,
                    false,
                    ignored -> {
                    }
            );

            assertNotNull(result);
            assertEquals("direct_pack", result.key());
            assertTrue(result.changed());
            assertTrue(result.restartRequired());
            assertEquals(1, requests.get());
            assertTrue(Files.isRegularFile(
                    packsFolder.toPath().resolve("direct_pack/dimensions/direct_pack.json")
            ));
            assertTrue(Files.isRegularFile(
                    packsFolder.toPath().resolve("direct_pack/dimensions/direct_pack_supporting.json")
            ));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void builtInPackPresenceRequiresItsPrimaryDimension() throws Exception {
        File packsFolder = temp.newFolder("managed-presence");
        Path dimensions = Files.createDirectories(
                packsFolder.toPath().resolve("underworld/dimensions")
        );
        writeDimension(packsFolder.toPath().resolve("underworld"), "underworld_roof");

        assertTrue(PackDownloader.isPackPresent(packsFolder, "underworld"));
        assertFalse(PackDownloader.isBuiltInPackPresent(packsFolder, "underworld"));

        writeDimension(packsFolder.toPath().resolve("underworld"), "underworld");
        assertTrue(Files.isDirectory(dimensions));
        assertTrue(PackDownloader.isBuiltInPackPresent(packsFolder, "underworld"));
    }

    @Test
    public void repairsManagedFolderMissingItsPrimaryDimension() throws Exception {
        File packsFolder = temp.newFolder("managed-repair-packs");
        Path target = packsFolder.toPath().resolve("underworld");
        Files.createDirectories(target.resolve("dimensions"));
        writeDimension(target, "underworld_roof");
        Files.writeString(target.resolve("partial.txt"), "partial", StandardCharsets.UTF_8);
        File extracted = writePack(temp.newFolder("managed-repair-source").toPath(), "underworld", "new");
        writeDimension(extracted.toPath(), "underworld_roof");

        PackDownloader.PackInstallResult result = PackDownloader.installExtractedPack(
                packsFolder,
                extracted,
                false,
                "underworld",
                ignored -> {
                }
        );

        assertNotNull(result);
        assertTrue(result.changed());
        assertTrue(result.restartRequired());
        assertTrue(Files.isRegularFile(target.resolve("dimensions/underworld.json")));
        assertTrue(Files.isRegularFile(target.resolve("dimensions/underworld_roof.json")));
        assertFalse(Files.exists(target.resolve("partial.txt")));
        assertTransactionStateClean(packsFolder);
    }

    @Test
    public void rejectsOtherPacksAsDefaultOverworld() {
        assertFalse(PackDownloader.isDefaultOverworld("theend"));
        assertFalse(PackDownloader.isDefaultOverworld(""));
        assertFalse(PackDownloader.isDefaultOverworld(null));
        assertFalse(PackDownloader.isBuiltInPack("theend"));
        assertFalse(PackDownloader.isBuiltInPack(""));
        assertFalse(PackDownloader.isBuiltInPack(null));
    }

    @Test
    public void isPackPresentRequiresNonEmptyFolder() throws IOException {
        File packsFolder = temp.newFolder("packs");

        assertFalse(PackDownloader.isPackPresent(packsFolder, "overworld"));
        assertFalse(PackDownloader.isPackPresent(packsFolder, null));
        assertFalse(PackDownloader.isPackPresent(packsFolder, ""));
        assertFalse(PackDownloader.isPackPresent(null, "overworld"));

        File pack = new File(packsFolder, "overworld");
        assertTrue(pack.mkdirs());
        assertFalse(PackDownloader.isPackPresent(packsFolder, "overworld"));

        // A partial import (content but no dimension file) counts as absent so it can be replaced.
        File biomes = new File(pack, "biomes");
        assertTrue(biomes.mkdirs());
        Files.writeString(new File(biomes, "plains.json").toPath(), "{}");
        assertFalse(PackDownloader.isPackPresent(packsFolder, "overworld"));

        File dimensions = new File(pack, "dimensions");
        assertTrue(dimensions.mkdirs());
        Files.writeString(new File(dimensions, "overworld.json").toPath(), "{}");
        assertTrue(PackDownloader.isPackPresent(packsFolder, "overworld"));
    }

    @Test
    public void packPresenceAcceptsSafeSymbolicPackDirectories() throws IOException {
        File packsFolder = temp.newFolder("linked-packs");
        Path external = temp.newFolder("external-pack").toPath();
        Files.createDirectories(external.resolve("dimensions"));
        Files.writeString(external.resolve("dimensions/overworld.json"), "{}");
        Path linked = packsFolder.toPath().resolve("overworld");
        try {
            Files.createSymbolicLink(linked, external);
        } catch (IOException | UnsupportedOperationException exception) {
            Assume.assumeNoException(exception);
        }

        assertTrue(PackDownloader.isPackPresent(packsFolder, "overworld"));

        Path externalFile = temp.newFile("outside-pack.txt").toPath();
        Files.createSymbolicLink(external.resolve("linked-file"), externalFile);
        assertFalse(PackDownloader.isPackPresent(packsFolder, "overworld"));
    }

    @Test
    public void downloadSkipsWhenExpectedPackAlreadyPresent() throws IOException {
        File packsFolder = temp.newFolder("packs");
        File dimensions = new File(packsFolder, "overworld/dimensions");
        assertTrue(dimensions.mkdirs());
        Files.writeString(new File(dimensions, "overworld.json").toPath(), "{}");

        List<String> feedback = new ArrayList<>();
        // The URL is unreachable on purpose: reaching the network would fail the download and
        // return null, so a non-null key proves the presence check ran before any fetch.
        PackDownloader.PackInstallResult result = PackDownloader.downloadBuiltIn(
                packsFolder,
                "overworld",
                false,
                feedback::add
        );

        assertEquals("overworld", result.key());
        assertFalse(result.changed());
        assertFalse(feedback.isEmpty());
    }

    @Test
    public void forceOverwritePublishesValidatedPackAndCleansTransactionState() throws Exception {
        File packsFolder = temp.newFolder("force-packs");
        File target = writePack(packsFolder.toPath().resolve("replaceable"), "replaceable", "old");
        Files.writeString(target.toPath().resolve("old-only.txt"), "old", StandardCharsets.UTF_8);
        File extracted = writePack(temp.newFolder("force-source").toPath(), "replaceable", "new");
        List<String> feedback = new ArrayList<>();

        PackDownloader.PackInstallResult result = PackDownloader.installExtractedPack(
                packsFolder,
                extracted,
                true,
                "replaceable",
                feedback::add
        );

        assertEquals(feedback.toString(), "replaceable", result.key());
        assertTrue(result.changed());
        assertTrue(result.restartRequired());
        assertEquals("new", Files.readString(target.toPath().resolve("state.txt"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(target.toPath().resolve("old-only.txt")));
        assertTransactionStateClean(packsFolder);
        assertEquals(0, PackDownloader.downloadLockCount());
    }

    @Test
    public void forceOverwritePreservesSymbolicPackTargets() throws Exception {
        File packsFolder = temp.newFolder("linked-target-packs");
        File external = writePack(temp.newFolder("linked-target-source").toPath(), "replaceable", "old");
        Path target = packsFolder.toPath().resolve("replaceable");
        try {
            Files.createSymbolicLink(target, external.toPath());
        } catch (IOException | UnsupportedOperationException exception) {
            Assume.assumeNoException(exception);
        }
        File extracted = writePack(temp.newFolder("linked-target-update").toPath(), "replaceable", "new");

        PackDownloader.PackInstallResult result = PackDownloader.installExtractedPack(
                packsFolder,
                extracted,
                true,
                "replaceable",
                ignored -> {
                }
        );

        assertNull(result);
        assertTrue(Files.isSymbolicLink(target));
        assertEquals("old", Files.readString(external.toPath().resolve("state.txt"), StandardCharsets.UTF_8));
        assertTransactionStateClean(packsFolder);
    }

    @Test
    public void invalidStagedPackPreservesExistingTarget() throws Exception {
        File packsFolder = temp.newFolder("invalid-packs");
        File target = writePack(packsFolder.toPath().resolve("protected_pack"), "protected_pack", "old");
        File extracted = writePack(temp.newFolder("invalid-source").toPath(), "protected_pack", "new");
        Files.delete(extracted.toPath().resolve("regions/local.json"));

        PackDownloader.PackInstallResult result = PackDownloader.installExtractedPack(
                packsFolder,
                extracted,
                true,
                "protected_pack",
                ignored -> {
                }
        );

        assertNull(result);
        assertEquals("old", Files.readString(target.toPath().resolve("state.txt"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(target.toPath().resolve("regions/local.json")));
        assertTransactionStateClean(packsFolder);
        assertEquals(0, PackDownloader.downloadLockCount());
    }

    @Test
    public void forceOverwriteReplacesEnginelessLoadedPackData() throws Exception {
        // A registered loader with no engines is a stale catalog registration (startup
        // validation registers one per visible pack); it must not block a forced update.
        File packsFolder = temp.newFolder("loaded-packs");
        File target = writePack(packsFolder.toPath().resolve("active_pack"), "active_pack", "old");
        File extracted = writePack(temp.newFolder("loaded-update").toPath(), "active_pack", "new");
        IrisData loaded = IrisData.get(target);
        try {
            PackDownloader.PackInstallResult result = PackDownloader.installExtractedPack(
                    packsFolder,
                    extracted,
                    true,
                    "active_pack",
                    ignored -> {
                    }
            );

            assertNotNull(result);
            assertEquals("new", Files.readString(target.toPath().resolve("state.txt"), StandardCharsets.UTF_8));
            assertTransactionStateClean(packsFolder);
        } finally {
            loaded.close();
        }
    }

    @Test
    public void forceOverwriteRefusesPackWithLiveEngines() throws Exception {
        File packsFolder = temp.newFolder("engine-packs");
        File target = writePack(packsFolder.toPath().resolve("active_pack"), "active_pack", "old");
        File extracted = writePack(temp.newFolder("engine-update").toPath(), "active_pack", "new");
        // Engines attach to detached openRuntime loaders in production, never to the cached
        // IrisData.get instance; the refusal gate must see this registration through the
        // engine index.
        IrisData loaded = IrisData.openRuntime(target);
        art.arcane.iris.engine.framework.Engine engine =
                org.mockito.Mockito.mock(art.arcane.iris.engine.framework.Engine.class);
        loaded.registerEngine(engine);
        try {
            PackDownloader.PackInstallResult result = PackDownloader.installExtractedPack(
                    packsFolder,
                    extracted,
                    true,
                    "active_pack",
                    ignored -> {
                    }
            );

            assertNull(result);
            assertEquals("old", Files.readString(target.toPath().resolve("state.txt"), StandardCharsets.UTF_8));
            assertTransactionStateClean(packsFolder);
        } finally {
            loaded.unregisterEngine(engine);
            loaded.close();
        }
    }

    @Test
    public void unexpectedDownloadedKeyPreservesExistingTarget() throws Exception {
        File packsFolder = temp.newFolder("mismatch-packs");
        File target = writePack(packsFolder.toPath().resolve("requested"), "requested", "old");
        File extracted = writePack(temp.newFolder("mismatch-source").toPath(), "different", "new");

        IOException failure = assertThrows(IOException.class, () -> PackDownloader.installExtractedPack(
                packsFolder,
                extracted,
                true,
                "requested",
                ignored -> {
                }
        ));

        assertTrue(failure.getMessage().contains("different"));
        assertTrue(failure.getMessage().contains("requested"));
        assertEquals("old", Files.readString(target.toPath().resolve("state.txt"), StandardCharsets.UTF_8));
        assertFalse(new File(packsFolder, "different").exists());
        assertTransactionStateClean(packsFolder);
        assertEquals(0, PackDownloader.downloadLockCount());
    }

    @Test
    public void importsExpectedDimensionFromMultiDimensionPack() throws Exception {
        File packsFolder = temp.newFolder("multi-dimension-packs");
        File extracted = writePack(temp.newFolder("multi-dimension-source").toPath(), "underworld", "new");
        writeDimension(extracted.toPath(), "underworld_roof");

        PackDownloader.PackInstallResult result = PackDownloader.installExtractedPack(
                packsFolder,
                extracted,
                false,
                "underworld",
                ignored -> {
                }
        );

        assertEquals("underworld", result.key());
        assertTrue(result.changed());
        assertTrue(Files.isRegularFile(
                packsFolder.toPath().resolve("underworld/dimensions/underworld_roof.json")
        ));
        assertTransactionStateClean(packsFolder);
    }

    @Test
    public void rejectsMultiDimensionPackWithoutExpectedDimension() throws Exception {
        File packsFolder = temp.newFolder("missing-dimension-packs");
        File extracted = writePack(temp.newFolder("missing-dimension-source").toPath(), "underworld", "new");
        writeDimension(extracted.toPath(), "underworld_roof");

        IOException failure = assertThrows(IOException.class, () -> PackDownloader.installExtractedPack(
                packsFolder,
                extracted,
                false,
                "missing",
                ignored -> {
                }
        ));

        assertTrue(failure.getMessage().contains("missing"));
        assertTrue(failure.getMessage().contains("underworld"));
        assertFalse(new File(packsFolder, "missing").exists());
        assertTransactionStateClean(packsFolder);
    }

    @Test
    public void selectsShortestDimensionKeyWhenDirectPackContainsMultipleDimensions() throws Exception {
        File packsFolder = temp.newFolder("ambiguous-dimension-packs");
        File extracted = writePack(temp.newFolder("ambiguous-dimension-source").toPath(), "underworld", "new");
        writeDimension(extracted.toPath(), "underworld_roof");
        List<String> feedback = new ArrayList<>();

        PackDownloader.PackInstallResult result = PackDownloader.installExtractedPack(
                packsFolder,
                extracted,
                false,
                null,
                feedback::add
        );

        assertNotNull(result);
        assertEquals("underworld", result.key());
        assertTrue(result.changed());
        assertTrue(Files.isRegularFile(
                packsFolder.toPath().resolve("underworld/dimensions/underworld_roof.json")
        ));
        assertTransactionStateClean(packsFolder);
    }

    @Test
    public void concurrentImportsForSameKeyPublishOnlyOnePack() throws Exception {
        File packsFolder = temp.newFolder("concurrent-packs");
        File firstSource = writePack(temp.newFolder("concurrent-first").toPath(), "shared_pack", "first");
        File secondSource = writePack(temp.newFolder("concurrent-second").toPath(), "shared_pack", "second");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PackDownloader.PackInstallResult> first = executor.submit(() -> {
                start.await();
                return PackDownloader.installExtractedPack(
                        packsFolder, firstSource, false, "shared_pack", ignored -> {
                        });
            });
            Future<PackDownloader.PackInstallResult> second = executor.submit(() -> {
                start.await();
                return PackDownloader.installExtractedPack(
                        packsFolder, secondSource, false, "shared_pack", ignored -> {
                        });
            });
            start.countDown();

            int successes = 0;
            PackDownloader.PackInstallResult firstResult = first.get();
            PackDownloader.PackInstallResult secondResult = second.get();
            if (firstResult != null && "shared_pack".equals(firstResult.key())) {
                successes++;
            }
            if (secondResult != null && "shared_pack".equals(secondResult.key())) {
                successes++;
            }

            assertEquals(1, successes);
            String installedState = Files.readString(
                    packsFolder.toPath().resolve("shared_pack/state.txt"),
                    StandardCharsets.UTF_8
            );
            assertTrue(installedState.equals("first") || installedState.equals("second"));
            assertTransactionStateClean(packsFolder);
            assertEquals(0, PackDownloader.downloadLockCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void rejectsUnsafeExpectedKeyBeforeDownload() throws IOException {
        File packsFolder = temp.newFolder("unsafe-key-packs");

        File extracted = writePack(temp.newFolder("unsafe-key-source").toPath(), "safe", "state");
        assertThrows(IllegalArgumentException.class, () -> PackDownloader.installExtractedPack(
                packsFolder, extracted, true, "../outside", ignored -> {
                }
        ));
        assertEquals(0, PackDownloader.downloadLockCount());
    }

    @Test
    public void boundedExtractionRejectsEscapedArchivePaths() throws Exception {
        Path archive = temp.newFile("escaped.zip").toPath();
        writeArchive(archive, Map.of("../escaped.txt", "outside"));
        Path destination = temp.newFolder("escaped-output").toPath();

        assertThrows(IOException.class, () -> PackDownloader.unpackArchive(
                archive,
                destination,
                new PackDownloader.ArchiveLimits(1024L, 10, 1024L, 1024L)
        ));
        assertFalse(Files.exists(destination.getParent().resolve("escaped.txt")));
    }

    @Test
    public void boundedExtractionEnforcesEntryAndExpandedLimits() throws Exception {
        Path archive = temp.newFile("bounded.zip").toPath();
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("first.txt", "1234");
        entries.put("second.txt", "5678");
        writeArchive(archive, entries);

        assertThrows(IOException.class, () -> PackDownloader.unpackArchive(
                archive,
                temp.newFolder("entry-count-output").toPath(),
                new PackDownloader.ArchiveLimits(4096L, 1, 4096L, 4096L)
        ));
        assertThrows(IOException.class, () -> PackDownloader.unpackArchive(
                archive,
                temp.newFolder("entry-size-output").toPath(),
                new PackDownloader.ArchiveLimits(4096L, 10, 4096L, 3L)
        ));
        assertThrows(IOException.class, () -> PackDownloader.unpackArchive(
                archive,
                temp.newFolder("expanded-output").toPath(),
                new PackDownloader.ArchiveLimits(4096L, 10, 7L, 4096L)
        ));
    }

    @Test
    public void boundedExtractionPublishesOnlyInsideItsDestination() throws Exception {
        Path archive = temp.newFile("valid.zip").toPath();
        writeArchive(archive, Map.of("pack/dimensions/overworld.json", "{}"));
        Path destination = temp.newFolder("valid-output").toPath();

        PackDownloader.unpackArchive(
                archive,
                destination,
                new PackDownloader.ArchiveLimits(4096L, 10, 4096L, 4096L)
        );

        assertEquals("{}", Files.readString(destination.resolve("pack/dimensions/overworld.json")));
    }

    private static File writePack(Path root, String key, String state) throws IOException {
        Files.createDirectories(root.resolve("dimensions"));
        Files.createDirectories(root.resolve("regions"));
        Files.createDirectories(root.resolve("biomes"));
        writeDimension(root, key);
        Files.writeString(
                root.resolve("regions/local.json"),
                "{\"name\":\"Local\",\"landBiomes\":[\"local\"]}",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                root.resolve("biomes/local.json"),
                "{\"name\":\"Local\",\"derivative\":\"minecraft:plains\"}",
                StandardCharsets.UTF_8
        );
        Files.writeString(root.resolve("state.txt"), state, StandardCharsets.UTF_8);
        return root.toFile();
    }

    private static void writeDimension(Path root, String key) throws IOException {
        Files.writeString(
                root.resolve("dimensions/" + key + ".json"),
                "{\"name\":\"" + key + "\",\"regions\":[\"local\"],\"logicalHeight\":256,"
                        + "\"dimensionHeight\":{\"min\":-64,\"max\":320}}",
                StandardCharsets.UTF_8
        );
    }

    private static void writeArchive(Path archive, Map<String, String> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void assertTransactionStateClean(File packsFolder) {
        File[] transactionEntries = packsFolder.listFiles((File parent, String name) ->
                name.startsWith(".iris-import-") || name.contains(".backup-"));
        assertTrue(transactionEntries == null || transactionEntries.length == 0);
    }
}
