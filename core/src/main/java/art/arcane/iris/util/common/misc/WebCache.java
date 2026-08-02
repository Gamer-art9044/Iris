/*
 * Iris is a World Generator for Minecraft Bukkit Servers
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

package art.arcane.iris.util.common.misc;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.volmlib.util.io.IO;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Download cache helpers over the platform data folder.
 *
 * <p>Every request is bounded: URL.openStream had no connect or read timeout, so a hung mirror parked the
 * calling thread (a command thread, or the boot pack prefetch) forever.
 */
public final class WebCache {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10L);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120L);
    private static final int BUFFER_SIZE = 8192;

    private static volatile HttpClient client;

    private WebCache() {
    }

    public static File getTemp() {
        return IrisPlatforms.get().dataFolder("cache", "temp");
    }

    public static File getCached(String name, String url) {
        String h = IO.hash(name + "@" + url);
        File f = IrisPlatforms.get().dataFile("cache", h.substring(0, 2), h.substring(3, 5), h);

        if (!f.exists()) {
            download(name, url, f);
        }

        return f.exists() ? f : null;
    }

    public static String getNonCached(String name, String url) {
        String h = IO.hash(name + "*" + url);
        File f = IrisPlatforms.get().dataFile("cache", h.substring(0, 2), h.substring(3, 5), h);

        if (!download(name, url, f)) {
            return "";
        }
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            IrisLogging.reportError(e);
            return "";
        }
    }

    public static File getNonCachedFile(String name, String url) {
        String h = IO.hash(name + "*" + url);
        File f = IrisPlatforms.get().dataFile("cache", h.substring(0, 2), h.substring(3, 5), h);
        IrisLogging.debug("Download " + name + " -> " + url);
        download(name, url, f);
        return f;
    }

    private static boolean download(String name, String url, File target) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                try (InputStream discard = response.body()) {
                    discard.readAllBytes();
                }
                IrisLogging.reportError(new IOException("HTTP " + response.statusCode()
                        + " downloading " + name + " from " + url));
                return false;
            }
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(target.toPath(),
                         StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            return true;
        } catch (IOException e) {
            IrisLogging.reportError(e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IrisLogging.reportError(e);
            return false;
        }
    }

    private static HttpClient client() {
        HttpClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (WebCache.class) {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return client;
        }
    }
}
