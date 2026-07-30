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

package art.arcane.iris.core;

import art.arcane.iris.Iris;
import art.arcane.iris.core.runtime.TransientWorldCleanupSupport;
import art.arcane.iris.core.tools.IrisCreator;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.misc.ServerProperties;
import art.arcane.iris.util.common.plugin.VolmitPlugin;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.io.IO;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent queue of world folders that must be deleted on the next startup, plus the startup
 * drain that actually removes them.
 */
public final class PendingWorldDeleteQueue {
    private static final String PENDING_WORLD_DELETE_FILE = "pending-world-deletes.txt";

    private final VolmitPlugin plugin;

    public PendingWorldDeleteQueue(VolmitPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized int queueWorldDeletionOnStartup(Collection<String> worldNames) throws IOException {
        if (worldNames == null || worldNames.isEmpty()) {
            return 0;
        }

        LinkedHashMap<String, String> queue = loadPendingWorldDeleteMap();
        int before = queue.size();

        for (String worldName : worldNames) {
            String normalized = normalizeWorldName(worldName);
            if (normalized == null) {
                continue;
            }
            queue.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }

        if (queue.size() != before) {
            writePendingWorldDeleteMap(queue);
        }

        return queue.size() - before;
    }

    public void processPendingStartupWorldDeletes() {
        try {
            try {
                int unregistered = IrisCreator.removeTransientStudioWorldsFromBukkitYml();
                if (unregistered > 0) {
                    Iris.info("Unregistered " + unregistered + " transient studio world(s) from bukkit.yml on startup.");
                }
            } catch (Throwable e) {
                Iris.reportError("Failed to unregister transient studio worlds from bukkit.yml on startup.", e);
            }

            LinkedHashMap<String, String> queue = loadPendingWorldDeleteMap();
            for (String transientStudioWorld : TransientWorldCleanupSupport.collectTransientStudioWorldNames(IrisWorldStorage.levelRoot())) {
                queue.putIfAbsent(transientStudioWorld.toLowerCase(Locale.ROOT), transientStudioWorld);
            }
            if (queue.isEmpty()) {
                return;
            }

            LinkedHashMap<String, String> remaining = new LinkedHashMap<>();
            for (String worldName : queue.values()) {
                if (worldName.equalsIgnoreCase(ServerProperties.LEVEL_NAME)) {
                    Iris.warn("Skipping queued deletion for \"" + worldName + "\" because it is configured as level-name.");
                    continue;
                }

                NamespacedKey worldKey = IrisWorldStorage.keyFromName(worldName);
                World loaded = WorldIdentity.resolve(worldKey).orElse(null);
                if (loaded != null) {
                    if (TransientWorldCleanupSupport.isTransientStudioWorldName(worldName)) {
                        try {
                            PlatformChunkGenerator generator = IrisToolbelt.access(loaded);
                            if (generator != null) {
                                generator.close();
                            }
                            IrisToolbelt.evacuate(loaded);
                            Bukkit.unloadWorld(loaded, false);
                            Iris.info("Unloaded leftover studio world \"" + worldName + "\" for deletion.");
                        } catch (Throwable e) {
                            Iris.reportError("Failed to unload leftover studio world \"" + worldName + "\".", e);
                        }

                        if (WorldIdentity.resolve(worldKey).isPresent()) {
                            Iris.warn("Studio world \"" + worldName + "\" is still loaded after unload; will retry next startup.");
                            remaining.put(worldName.toLowerCase(Locale.ROOT), worldName);
                            continue;
                        }
                    } else {
                        Iris.warn("Skipping queued deletion for \"" + worldName + "\" because it is currently loaded.");
                        remaining.put(worldName.toLowerCase(Locale.ROOT), worldName);
                        continue;
                    }
                }

                boolean foundAny = false;
                boolean deletedAll = true;
                for (String familyWorldName : TransientWorldCleanupSupport.worldFamilyNames(worldName)) {
                    File worldFolder = IrisWorldStorage.dimensionRoot(familyWorldName);
                    if (!worldFolder.exists()) {
                        continue;
                    }

                    foundAny = true;
                    IO.delete(worldFolder);
                    if (worldFolder.exists()) {
                        deletedAll = false;
                        Iris.warn("Failed to delete queued world folder \"" + familyWorldName + "\". Retrying on next startup.");
                    } else {
                        Iris.info("Deleted queued world folder \"" + familyWorldName + "\".");
                    }
                }

                if (!foundAny) {
                    Iris.info("Queued world deletion skipped for \"" + worldName + "\" (folder missing).");
                    continue;
                }

                if (!deletedAll) {
                    remaining.put(worldName.toLowerCase(Locale.ROOT), worldName);
                    continue;
                }
            }

            writePendingWorldDeleteMap(remaining);
        } catch (Throwable e) {
            Iris.error("Failed to process queued startup world deletions.");
            Iris.reportError(e);
            e.printStackTrace();
        }
    }

    private LinkedHashMap<String, String> loadPendingWorldDeleteMap() throws IOException {
        LinkedHashMap<String, String> queue = new LinkedHashMap<>();
        File queueFile = plugin.getDataFile(PENDING_WORLD_DELETE_FILE);
        if (!queueFile.exists()) {
            return queue;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(queueFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalizeWorldName(line);
                if (normalized == null) {
                    continue;
                }
                queue.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
            }
        }

        return queue;
    }

    private void writePendingWorldDeleteMap(Map<String, String> queue) throws IOException {
        File queueFile = plugin.getDataFile(PENDING_WORLD_DELETE_FILE);
        if (queue.isEmpty()) {
            if (queueFile.exists()) {
                IO.delete(queueFile);
            }
            return;
        }

        File parent = queueFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create queue directory: " + parent.getAbsolutePath());
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(queueFile))) {
            for (String worldName : queue.values()) {
                writer.println(worldName);
            }
        }
    }

    @Nullable
    private static String normalizeWorldName(String worldName) {
        if (worldName == null) {
            return null;
        }

        String trimmed = worldName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed;
    }
}
