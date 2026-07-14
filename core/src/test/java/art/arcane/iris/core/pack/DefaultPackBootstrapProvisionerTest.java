package art.arcane.iris.core.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DefaultPackBootstrapProvisionerTest {
    @Test
    public void coldInstallUsesFreshCacheWithoutSecondRequest() throws Exception {
        byte[] archive = packArchive("overworld", "bootstrap_biome");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(archive, requests);
        Path root = Files.createTempDirectory("iris-bootstrap-cold");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            Path legacyDatapack = dataDirectory.resolve("bootstrap/datapack");
            Files.createDirectories(legacyDatapack);
            Files.writeString(legacyDatapack.resolve("obsolete.txt"), "obsolete", StandardCharsets.UTF_8);
            DefaultPackBootstrapProvisioner.ProvisionOptions options = options(server, root, Duration.ofHours(1));
            DefaultPackBootstrapProvisioner.ProvisionResult installed = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            );
            DefaultPackBootstrapProvisioner.ProvisionResult unchanged = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            );

            assertEquals(DefaultPackBootstrapProvisioner.ProvisionStatus.INSTALLED, installed.status());
            assertEquals(DefaultPackBootstrapProvisioner.ProvisionStatus.UNCHANGED, unchanged.status());
            assertEquals(1, requests.get());
            assertTrue(Files.isRegularFile(installed.packRoot().resolve("dimensions/overworld.json")));
            assertEquals(root.resolve("datapacks/iris"), installed.datapackRoot());
            assertTrue(Files.isRegularFile(installed.datapackRoot().resolve("pack.mcmeta")));
            assertTrue(Files.isRegularFile(installed.datapackRoot().resolve("data/overworld/worldgen/biome/bootstrap_biome.json")));
            assertFalse(Files.exists(dataDirectory.resolve("bootstrap/datapack")));
            assertTrue(DefaultPackBootstrapProvisioner.isProvisioned(dataDirectory, root));
            assertTrue(DefaultPackBootstrapProvisioner.wasProvisionedThisStartup());
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    @Test
    public void missingArchiveRebuildsWorldDatapackFromValidatedInstalledPack() throws Exception {
        byte[] archive = packArchive("overworld", "bootstrap_biome");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(archive, requests);
        boolean serverStopped = false;
        Path root = Files.createTempDirectory("iris-bootstrap-offline-migration");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            DefaultPackBootstrapProvisioner.ProvisionOptions options = options(server, root, Duration.ofHours(1));
            DefaultPackBootstrapProvisioner.ProvisionResult installed = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            );
            Files.delete(dataDirectory.resolve("cache/bootstrap/default-overworld.zip"));
            delete(installed.datapackRoot());
            server.stop(0);
            serverStopped = true;

            DefaultPackBootstrapProvisioner.ProvisionResult rebuilt = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            );

            assertEquals(DefaultPackBootstrapProvisioner.ProvisionStatus.UPDATED, rebuilt.status());
            assertEquals(1, requests.get());
            assertTrue(Files.isRegularFile(rebuilt.datapackRoot().resolve("pack.mcmeta")));
            assertTrue(Files.isRegularFile(rebuilt.datapackRoot().resolve("data/overworld/worldgen/biome/bootstrap_biome.json")));
        } finally {
            if (!serverStopped) {
                server.stop(0);
            }
            delete(root);
        }
    }

    @Test
    public void corruptCacheRedownloadsAndRepairsArchive() throws Exception {
        byte[] archive = packArchive("overworld", "bootstrap_biome");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(archive, requests);
        Path root = Files.createTempDirectory("iris-bootstrap-corrupt-cache");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            DefaultPackBootstrapProvisioner.ProvisionOptions options = options(server, root, Duration.ofHours(1));
            DefaultPackBootstrapProvisioner.provision(dataDirectory, ignored -> {
            }, options);
            Path cache = dataDirectory.resolve("cache/bootstrap/default-overworld.zip");
            Files.writeString(cache, "corrupt", StandardCharsets.UTF_8);

            DefaultPackBootstrapProvisioner.provision(dataDirectory, ignored -> {
            }, options);

            assertEquals(2, requests.get());
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(cache))) {
                assertTrue(zip.getNextEntry() != null);
            }
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    @Test
    public void traversalArchiveFailsWithoutMarkerOrEscape() throws Exception {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        files.put("dimensions/overworld.json", dimensionJson("overworld"));
        files.put("../escape.txt", "unsafe");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(zip(files), requests);
        Path root = Files.createTempDirectory("iris-bootstrap-traversal");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            DefaultPackBootstrapProvisioner.ProvisionOptions options = options(server, root, Duration.ZERO);

            assertThrows(IOException.class, () -> DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            ));
            assertFalse(Files.exists(dataDirectory.resolve("bootstrap/provisioned.properties")));
            assertFalse(Files.exists(root.resolve("escape.txt")));
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    @Test
    public void missingDimensionLayoutFailsWithoutInstallingPack() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(zip(Map.of("README.md", "not an Iris pack")), requests);
        Path root = Files.createTempDirectory("iris-bootstrap-layout");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            DefaultPackBootstrapProvisioner.ProvisionOptions options = options(server, root, Duration.ZERO);

            assertThrows(IOException.class, () -> DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            ));
            assertFalse(Files.exists(dataDirectory.resolve("packs/overworld")));
            assertFalse(Files.exists(dataDirectory.resolve("bootstrap/provisioned.properties")));
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    @Test
    public void existingSymlinkedPackIsCompiledWithoutDownloadOrReplacement() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(packArchive("remote", "remote_biome"), requests);
        Path root = Files.createTempDirectory("iris-bootstrap-symlink");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            Path target = root.resolve("custom-overworld");
            writePack(target, "overworld", "local_biome");
            Files.createDirectories(dataDirectory.resolve("packs"));
            Path link = dataDirectory.resolve("packs/overworld");
            Files.createSymbolicLink(link, target);
            DefaultPackBootstrapProvisioner.ProvisionOptions options = options(server, root, Duration.ZERO);

            DefaultPackBootstrapProvisioner.ProvisionResult result = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            );

            assertEquals(0, requests.get());
            assertTrue(Files.isSymbolicLink(link));
            assertEquals(target.toRealPath(), link.toRealPath());
            assertTrue(Files.isRegularFile(result.datapackRoot().resolve("data/overworld/worldgen/biome/local_biome.json")));

            Files.writeString(target.resolve("biomes/local.json"), biomeJson("changed_biome"), StandardCharsets.UTF_8);
            assertFalse(DefaultPackBootstrapProvisioner.isProvisioned(dataDirectory, root));
            DefaultPackBootstrapProvisioner.ProvisionResult updated = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            );
            assertEquals(DefaultPackBootstrapProvisioner.ProvisionStatus.UPDATED, updated.status());
            assertEquals(0, requests.get());
            assertTrue(Files.isSymbolicLink(link));
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    @Test
    public void additionalPackRebuildsAggregateWithoutNetwork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(packArchive("overworld", "first_biome"), requests);
        Path root = Files.createTempDirectory("iris-bootstrap-aggregate");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            DefaultPackBootstrapProvisioner.ProvisionOptions options = options(server, root, Duration.ofHours(1));
            DefaultPackBootstrapProvisioner.provision(dataDirectory, ignored -> {
            }, options);
            writePack(dataDirectory.resolve("packs/second"), "second", "second_biome");
            writePack(root.resolve("dimensions/example/world/iris/pack"), "world_local", "world_local_biome");

            assertFalse(DefaultPackBootstrapProvisioner.isProvisioned(dataDirectory, root));
            DefaultPackBootstrapProvisioner.ProvisionResult updated = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            );

            assertEquals(DefaultPackBootstrapProvisioner.ProvisionStatus.UPDATED, updated.status());
            assertEquals(1, requests.get());
            assertTrue(Files.isRegularFile(updated.datapackRoot().resolve("data/second/worldgen/biome/second_biome.json")));
            assertTrue(Files.isRegularFile(updated.datapackRoot().resolve("data/world_local/worldgen/biome/world_local_biome.json")));
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    @Test
    public void localEditRelinquishesManagedOwnershipWithoutRemoteReplacement() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(packArchive("overworld", "managed_biome"), requests);
        Path root = Files.createTempDirectory("iris-bootstrap-managed-edit");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            DefaultPackBootstrapProvisioner.ProvisionOptions initialOptions = options(server, root, Duration.ofHours(1));
            DefaultPackBootstrapProvisioner.provision(dataDirectory, ignored -> {
            }, initialOptions);
            Path editedBiome = dataDirectory.resolve("packs/overworld/biomes/local.json");
            Files.writeString(editedBiome, biomeJson("locally_edited_biome"), StandardCharsets.UTF_8);
            DefaultPackBootstrapProvisioner.ProvisionOptions refreshOptions = options(server, root, Duration.ZERO);

            DefaultPackBootstrapProvisioner.ProvisionResult updated = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    refreshOptions
            );

            assertEquals(1, requests.get());
            assertTrue(Files.readString(editedBiome).contains("locally_edited_biome"));
            assertTrue(Files.isRegularFile(updated.datapackRoot().resolve("data/overworld/worldgen/biome/locally_edited_biome.json")));
            Properties marker = new Properties();
            try (java.io.InputStream input = Files.newInputStream(dataDirectory.resolve("bootstrap/provisioned.properties"))) {
                marker.load(input);
            }
            assertEquals("false", marker.getProperty("managedDefault"));
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    @Test
    public void failedAggregateCompilationPreservesPreviousOutputAndMarker() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(packArchive("overworld", "first_biome"), requests);
        Path root = Files.createTempDirectory("iris-bootstrap-rollback");
        try {
            Path dataDirectory = root.resolve("plugins/Iris");
            DefaultPackBootstrapProvisioner.ProvisionOptions options = options(server, root, Duration.ofHours(1));
            DefaultPackBootstrapProvisioner.ProvisionResult first = DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            );
            byte[] marker = Files.readAllBytes(dataDirectory.resolve("bootstrap/provisioned.properties"));
            byte[] metadata = Files.readAllBytes(first.datapackRoot().resolve("pack.mcmeta"));
            Path invalidPack = dataDirectory.resolve("packs/invalid");
            Files.createDirectories(invalidPack.resolve("dimensions"));
            Files.writeString(invalidPack.resolve("dimensions/broken.json"), "{", StandardCharsets.UTF_8);

            assertThrows(IOException.class, () -> DefaultPackBootstrapProvisioner.provision(
                    dataDirectory,
                    ignored -> {
                    },
                    options
            ));
            assertTrue(java.util.Arrays.equals(marker, Files.readAllBytes(dataDirectory.resolve("bootstrap/provisioned.properties"))));
            assertTrue(java.util.Arrays.equals(metadata, Files.readAllBytes(first.datapackRoot().resolve("pack.mcmeta"))));
        } finally {
            server.stop(0);
            delete(root);
        }
    }

    @Test
    public void resolvesConfiguredLevelRootFromServerProperties() throws Exception {
        Path serverRoot = Files.createTempDirectory("iris-bootstrap-level-root");
        try {
            Files.writeString(
                    serverRoot.resolve("server.properties"),
                    "level-name=levels/primary\n",
                    StandardCharsets.UTF_8
            );

            assertEquals(
                    serverRoot.resolve("levels/primary").normalize(),
                    DefaultPackBootstrapProvisioner.resolveLevelRoot(serverRoot)
            );
        } finally {
            delete(serverRoot);
        }
    }

    private static DefaultPackBootstrapProvisioner.ProvisionOptions options(
            HttpServer server,
            Path serverRoot,
            Duration refreshInterval
    ) {
        URI source = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/overworld.zip");
        return new DefaultPackBootstrapProvisioner.ProvisionOptions(
                source,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC),
                refreshInterval,
                Duration.ofSeconds(2),
                1,
                Duration.ZERO,
                8L * 1024L * 1024L,
                serverRoot
        );
    }

    private static HttpServer server(byte[] response, AtomicInteger requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/overworld.zip", exchange -> respond(exchange, response, requests));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, byte[] response, AtomicInteger requests) throws IOException {
        requests.incrementAndGet();
        exchange.getResponseHeaders().add("Content-Type", "application/zip");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static byte[] packArchive(String dimensionKey, String biomeId) throws IOException {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        files.put("dimensions/" + dimensionKey + ".json", dimensionJson(dimensionKey));
        files.put("regions/local.json", "{\"name\":\"Local\",\"landBiomes\":[\"local\"]}");
        files.put("biomes/local.json", biomeJson(biomeId));
        return zip(files);
    }

    private static void writePack(Path root, String dimensionKey, String biomeId) throws IOException {
        Files.createDirectories(root.resolve("dimensions"));
        Files.createDirectories(root.resolve("regions"));
        Files.createDirectories(root.resolve("biomes"));
        Files.writeString(root.resolve("dimensions/" + dimensionKey + ".json"), dimensionJson(dimensionKey), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("regions/local.json"), "{\"name\":\"Local\",\"landBiomes\":[\"local\"]}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("biomes/local.json"), biomeJson(biomeId), StandardCharsets.UTF_8);
    }

    private static String dimensionJson(String name) {
        return "{\"name\":\"" + name + "\",\"regions\":[\"local\"],\"logicalHeight\":256,\"dimensionHeight\":{\"min\":-64,\"max\":320}}";
    }

    private static String biomeJson(String biomeId) {
        return "{\"name\":\"Local\",\"derivative\":\"minecraft:plains\",\"customDerivitives\":[{\"id\":\""
                + biomeId + "\",\"category\":\"plains\"}]}";
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
