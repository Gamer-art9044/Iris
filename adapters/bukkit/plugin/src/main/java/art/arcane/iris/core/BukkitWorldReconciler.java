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
import art.arcane.iris.core.lifecycle.WorldLifecycleService;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.platform.bukkit.BukkitEnvironment;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.collection.KList;
import org.bukkit.NamespacedKey;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;

import java.util.function.Predicate;

/**
 * Loads Iris worlds that are staged in bukkit.yml but not yet present on the server.
 */
public final class BukkitWorldReconciler {
    private final Iris plugin;

    public BukkitWorldReconciler(Iris plugin) {
        this.plugin = plugin;
    }

    public void checkForBukkitWorlds(Predicate<String> filter) {
        try {
            KList<String> deferredStartupWorlds = new KList<>();
            IrisWorlds.readBukkitWorlds().forEach((s, generator) -> {
                try {
                    NamespacedKey worldKey = IrisWorldStorage.keyFromName(s);
                    if (WorldIdentity.resolve(worldKey).isPresent() || !filter.test(s)) return;

                    Iris.info("Loading World: %s | Generator: %s", s, generator);
                    ChunkGenerator gen = plugin.getDefaultWorldGenerator(s, generator);
                    IrisDimension dim = IrisWorldGeneratorResolver.loadDimension(s, generator);
                    assert dim != null && gen != null;

                    Iris.info(C.LIGHT_PURPLE + "Preparing Spawn for " + s + "' using Iris:" + generator + "...");
                    WorldCreator c = WorldCreator.ofKey(worldKey)
                            .generator(gen)
                            .environment(BukkitEnvironment.from(dim.getEnvironment()));
                    Long stagedSeed = IrisWorlds.readBukkitWorldSeed(s);
                    if (stagedSeed != null) {
                        c.seed(stagedSeed);
                    }
                    INMS.get().createWorld(c);
                    Iris.info(C.LIGHT_PURPLE + "Loaded " + s + "!");
                } catch (Throwable e) {
                    if (containsCreateWorldUnsupportedOperation(e)) {
                        if (J.isFolia()) {
                            if (!deferredStartupWorlds.contains(s)) {
                                deferredStartupWorlds.add(s);
                            }
                            return;
                        }
                        Iris.error("Failed to load world " + s + "!");
                        Iris.error("This server denied Bukkit.createWorld for \"" + s + "\" at the current startup phase.");
                        Iris.error("Ensure Iris is loaded at STARTUP and restart after staging worlds in bukkit.yml.");
                        Iris.reportError("Failed to load staged startup world \"" + s + "\".", e);
                        return;
                    }
                    Iris.reportError("Failed to load startup world \"" + s + "\".", e);
                }
            });
            if (!deferredStartupWorlds.isEmpty()) {
                Iris.warn("Staged Iris worlds could not load on Folia: %s", String.join(", ", deferredStartupWorlds));
                Iris.warn("Bukkit.createWorld is unsupported on this server and the Iris runtime world backend is unavailable (%s).", WorldLifecycleService.get().capabilities().paperLikeResolution());
            }
        } catch (Throwable e) {
            Iris.reportError("Failed while loading startup Iris worlds.", e);
        }
    }

    private static boolean containsCreateWorldUnsupportedOperation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof UnsupportedOperationException || cursor instanceof IllegalStateException) {
                for (StackTraceElement element : cursor.getStackTrace()) {
                    if ("org.bukkit.craftbukkit.CraftServer".equals(element.getClassName())
                            && "createWorld".equals(element.getMethodName())) {
                        return true;
                    }
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
