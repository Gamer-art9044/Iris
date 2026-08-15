package art.arcane.iris.util.common.misc;

import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.volmlib.util.io.IO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Answers;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WebCacheTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private IrisPlatform previousPlatform;

    @Before
    public void bindPlatform() {
        previousPlatform = IrisPlatforms.isBound() ? IrisPlatforms.get() : null;
        IrisPlatforms.unbind();
        IrisPlatform platform = mock(IrisPlatform.class, Answers.CALLS_REAL_METHODS);
        when(platform.dataFolder()).thenReturn(temp.getRoot());
        when(platform.dataFile(any(String[].class))).thenAnswer(invocation -> {
            File file = temp.getRoot();
            for (Object argument : invocation.getArguments()) {
                file = new File(file, String.valueOf(argument));
            }
            return file;
        });
        IrisPlatforms.bind(platform);
    }

    @After
    public void restorePlatform() {
        IrisPlatforms.unbind();
        if (previousPlatform != null) {
            IrisPlatforms.bind(previousPlatform);
        }
    }

    @Test
    public void declaredOversizeDoesNotReplaceThePreviousCacheEntry() throws Exception {
        byte[] body = "archive-larger-than-limit".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(body, true);
        try {
            String name = "declared-pack";
            String url = url(server);
            File existing = cachedFile(name, url);
            Files.createDirectories(existing.toPath().getParent());
            Files.writeString(existing.toPath(), "previous", StandardCharsets.UTF_8);

            File downloaded = WebCache.getNonCachedFile(name, url, 8L);

            assertNull(downloaded);
            assertEquals("previous", Files.readString(existing.toPath(), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamedOversizeDoesNotPublishAPartialDownload() throws Exception {
        byte[] body = "chunked-archive-larger-than-limit".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(body, false);
        try {
            String name = "chunked-pack";
            String url = url(server);
            File existing = cachedFile(name, url);
            Files.createDirectories(existing.toPath().getParent());
            Files.writeString(existing.toPath(), "previous", StandardCharsets.UTF_8);

            File downloaded = WebCache.getNonCachedFile(name, url, 8L);

            assertNull(downloaded);
            assertEquals("previous", Files.readString(existing.toPath(), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void boundedDownloadPublishesTheCompleteResponse() throws Exception {
        byte[] body = "valid-archive".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(body, true);
        try {
            String name = "valid-pack";
            String url = url(server);

            File downloaded = WebCache.getNonCachedFile(name, url, body.length);

            assertTrue(downloaded.isFile());
            assertEquals("valid-archive", Files.readString(downloaded.toPath(), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void knownLengthReportsMonotonicStartAndFinalProgress() throws Exception {
        byte[] body = "known-length-archive".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(body, true);
        try {
            List<WebCache.TransferProgress> progress = new ArrayList<>();

            File downloaded = WebCache.getNonCachedFile(
                    "known-progress",
                    url(server),
                    body.length,
                    progress::add
            );

            assertNotNull(downloaded);
            assertTrue(progress.size() >= 2);
            assertEquals(0L, progress.get(0).transferredBytes());
            assertEquals(body.length, progress.get(0).contentLength());
            assertEquals(0L, progress.get(0).elapsedMillis());
            assertFalse(progress.get(0).complete());
            assertProgressEndsAt(progress, body.length, body.length);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void unknownLengthReportsMonotonicStartAndFinalProgress() throws Exception {
        byte[] body = "unknown-length-archive".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(body, false);
        try {
            List<WebCache.TransferProgress> progress = new ArrayList<>();

            File downloaded = WebCache.getNonCachedFile(
                    "unknown-progress",
                    url(server),
                    body.length,
                    progress::add
            );

            assertNotNull(downloaded);
            assertTrue(progress.size() >= 2);
            assertEquals(-1L, progress.get(0).contentLength());
            assertEquals(0L, progress.get(0).elapsedMillis());
            assertProgressEndsAt(progress, body.length, -1L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void progressListenerFailureDoesNotCorruptTheDownload() throws Exception {
        byte[] body = "listener-safe-archive".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(body, true);
        try {
            AtomicInteger callbacks = new AtomicInteger();

            File downloaded = WebCache.getNonCachedFile(
                    "listener-failure",
                    url(server),
                    body.length,
                    progress -> {
                        callbacks.incrementAndGet();
                        throw new IllegalStateException("listener failure");
                    }
            );

            assertNotNull(downloaded);
            assertTrue(callbacks.get() >= 2);
            assertEquals("listener-safe-archive", Files.readString(downloaded.toPath(), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(byte[] body, boolean declareLength) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pack", exchange -> respond(exchange, body, declareLength));
        server.start();
        return server;
    }

    private void respond(HttpExchange exchange, byte[] body, boolean declareLength) throws IOException {
        exchange.sendResponseHeaders(200, declareLength ? body.length : 0L);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/pack";
    }

    private File cachedFile(String name, String url) {
        String hash = IO.hash(name + "*" + url);
        return IrisPlatforms.get().dataFile("cache", hash.substring(0, 2), hash.substring(3, 5), hash);
    }

    private void assertProgressEndsAt(List<WebCache.TransferProgress> progress, long transferredBytes,
                                      long contentLength) {
        long previousBytes = -1L;
        long previousElapsed = -1L;
        for (int index = 0; index < progress.size(); index++) {
            WebCache.TransferProgress update = progress.get(index);
            assertTrue(update.transferredBytes() >= previousBytes);
            assertTrue(update.elapsedMillis() >= previousElapsed);
            assertEquals(contentLength, update.contentLength());
            assertEquals(index == progress.size() - 1, update.complete());
            previousBytes = update.transferredBytes();
            previousElapsed = update.elapsedMillis();
        }
        assertEquals(transferredBytes, progress.get(progress.size() - 1).transferredBytes());
    }
}
