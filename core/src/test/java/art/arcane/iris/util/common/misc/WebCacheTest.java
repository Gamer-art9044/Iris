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

import static org.junit.Assert.assertEquals;
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
}
