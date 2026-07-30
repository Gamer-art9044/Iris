/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.engine;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisEngineData;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.volmlib.util.io.IO;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * On-disk persistence for a single engine's {@link IrisEngineData}. Loads are double-checked against
 * the engine's volatile field and serialized on a dedicated lock, and every write goes through an
 * atomic temp-file move so a crash mid-save can never truncate the live engine data.
 */
final class EngineDataStore {
    private final IrisEngine engine;
    private final Object engineDataLock = new Object();

    EngineDataStore(IrisEngine engine) {
        this.engine = engine;
    }

    IrisEngineData getEngineData() {
        IrisEngineData loaded = engine.engineData;
        if (loaded != null) {
            return loaded;
        }
        synchronized (engineDataLock) {
            loaded = engine.engineData;
            if (loaded != null) {
                return loaded;
            }
            File f = new File(engine.getWorld().worldFolder(), "iris/engine-data/" + engine.getDimension().getLoadKey() + ".json");
            if (f.exists()) {
                try {
                    loaded = new Gson().fromJson(IO.readAll(f), IrisEngineData.class);
                    if (loaded == null) {
                        throw new IllegalStateException("Engine data file contains no JSON object: " + f.getAbsolutePath());
                    }
                } catch (IOException | JsonParseException e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                    throw new IllegalStateException("Failed to read Iris engine data without modifying it: " + f.getAbsolutePath(), e);
                }
            }

            if (loaded == null) {
                loaded = new IrisEngineData();
                loaded.getStatistics().setVersion(IrisPlatforms.get().irisVersionNumber());
                loaded.getStatistics().setMCVersion(IrisPlatforms.get().minecraftVersionNumber());
                loaded.getStatistics().setUpgradedVersion(IrisPlatforms.get().irisVersionNumber());
                if (loaded.getStatistics().getVersion() == -1 || loaded.getStatistics().getMCVersion() == -1) {
                    IrisLogging.error("Failed to setup Engine Data!");
                }
                try {
                    writeEngineDataAtomically(f, loaded);
                } catch (IOException e) {
                    IrisLogging.reportError(e);
                    e.printStackTrace();
                    throw new IllegalStateException("Failed to create Iris engine data: " + f.getAbsolutePath(), e);
                }
            }
            engine.engineData = loaded;
            return loaded;
        }
    }

    void saveEngineData() {
        synchronized (engineDataLock) {
            File f = new File(engine.getWorld().worldFolder(), "iris/engine-data/" + engine.getDimension().getLoadKey() + ".json");
            try {
                writeEngineDataAtomically(f, engine.getEngineData());
                IrisLogging.debug("Saved Engine Data");
            } catch (IOException e) {
                IrisLogging.error("Failed to save Engine Data");
                IrisLogging.reportError(e);
                e.printStackTrace();
                throw new IllegalStateException("Failed to save Iris engine data: " + f.getAbsolutePath(), e);
            }
        }
    }

    void releaseEngineData() {
        IrisData data = engine.getData();
        data.unregisterEngine(engine);
        if (data.getEngines().isEmpty()) {
            data.close();
            data.clearLists();
        }
    }

    static void writeEngineDataAtomically(File file, IrisEngineData data) throws IOException {
        Path output = file.toPath();
        Path parent = output.getParent();
        if (parent == null) {
            throw new IOException("Engine data path has no parent: " + output);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
