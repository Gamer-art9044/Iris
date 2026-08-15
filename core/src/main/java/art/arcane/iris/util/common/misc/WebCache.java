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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
    private static final long PROGRESS_INTERVAL_NANOS = Duration.ofMillis(250L).toNanos();
    private static final TransferProgressListener NO_TRANSFER_PROGRESS = progress -> {
    };

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
        return getNonCachedFile(name, url, Long.MAX_VALUE);
    }

    public static File getNonCachedFile(String name, String url, long maxBytes) {
        return getNonCachedFile(name, url, maxBytes, NO_TRANSFER_PROGRESS);
    }

    public static File getNonCachedFile(String name, String url, TransferProgressListener progressListener) {
        return getNonCachedFile(name, url, Long.MAX_VALUE, progressListener);
    }

    public static File getNonCachedFile(String name, String url, long maxBytes,
                                        TransferProgressListener progressListener) {
        String h = IO.hash(name + "*" + url);
        File f = IrisPlatforms.get().dataFile("cache", h.substring(0, 2), h.substring(3, 5), h);
        IrisLogging.debug("Download " + name);
        return download(name, url, f, maxBytes, progressListener) ? f : null;
    }

    private static boolean download(String name, String url, File target) {
        return download(name, url, target, Long.MAX_VALUE, NO_TRANSFER_PROGRESS);
    }

    private static boolean download(String name, String url, File target, long maxBytes) {
        return download(name, url, target, maxBytes, NO_TRANSFER_PROGRESS);
    }

    private static boolean download(String name, String url, File target, long maxBytes,
                                    TransferProgressListener progressListener) {
        if (maxBytes < 1L) {
            throw new IllegalArgumentException("Download size limit must be positive.");
        }
        TransferProgressListener progress = progressListener == null ? NO_TRANSFER_PROGRESS : progressListener;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        Path staged = null;
        try {
            checkInterrupted();
            HttpResponse<InputStream> response = client()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                IrisLogging.reportError(new IOException("HTTP " + response.statusCode()
                        + " downloading " + name));
                return false;
            }
            long declaredBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredBytes > maxBytes) {
                response.body().close();
                throw new IOException("Download exceeds the size limit for " + name + ".");
            }
            Path destination = target.toPath().toAbsolutePath().normalize();
            Path parent = destination.getParent();
            if (parent == null) {
                response.body().close();
                throw new IOException("Download target has no parent: " + destination);
            }
            Files.createDirectories(parent);
            staged = Files.createTempFile(parent, ".download-", ".tmp");
            long startedNanos = System.nanoTime();
            long lastProgressNanos = startedNanos;
            sendProgress(progress, new TransferProgress(0L, declaredBytes, 0L, false));
            long downloadedBytes = 0L;
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(staged, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while (true) {
                    checkInterrupted();
                    read = in.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    checkInterrupted();
                    if (read > maxBytes - downloadedBytes) {
                        throw new IOException("Download exceeds the size limit for " + name + ".");
                    }
                    out.write(buffer, 0, read);
                    downloadedBytes += read;
                    long currentNanos = System.nanoTime();
                    if (currentNanos - lastProgressNanos >= PROGRESS_INTERVAL_NANOS) {
                        sendProgress(progress, new TransferProgress(
                                downloadedBytes,
                                declaredBytes,
                                elapsedMillis(startedNanos, currentNanos),
                                false
                        ));
                        lastProgressNanos = currentNanos;
                    }
                }
                out.flush();
            }
            sendProgress(progress, new TransferProgress(
                    downloadedBytes,
                    declaredBytes,
                    elapsedMillis(startedNanos),
                    true
            ));
            checkInterrupted();
            try {
                Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            staged = null;
            return true;
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                IrisLogging.debug("Download interrupted for " + name);
            } else {
                IrisLogging.reportError(e);
            }
            return false;
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                IrisLogging.debug("Download interrupted for " + name);
                return false;
            }
            IrisLogging.reportError(e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IrisLogging.debug("Download interrupted for " + name);
            return false;
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException cleanupFailure) {
                    IrisLogging.reportError("Failed to clean incomplete download " + staged + ".", cleanupFailure);
                }
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download interrupted.");
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return elapsedMillis(startedNanos, System.nanoTime());
    }

    private static long elapsedMillis(long startedNanos, long currentNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, currentNanos - startedNanos));
    }

    private static void sendProgress(TransferProgressListener listener, TransferProgress progress) {
        try {
            listener.onProgress(progress);
        } catch (RuntimeException exception) {
            IrisLogging.reportError("Download progress delivery failed", exception);
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

    public record TransferProgress(long transferredBytes, long contentLength, long elapsedMillis, boolean complete) {
    }

    @FunctionalInterface
    public interface TransferProgressListener {
        void onProgress(TransferProgress progress);
    }
}
