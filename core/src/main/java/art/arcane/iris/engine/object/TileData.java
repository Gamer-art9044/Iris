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

package art.arcane.iris.engine.object;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.PlatformBlockState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.util.common.reflect.KeyedType;
import art.arcane.volmlib.util.collection.KMap;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

@SuppressWarnings("ALL")
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TileData implements Cloneable {
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.LENIENT).setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final boolean BUKKIT_PRESENT = detectBukkit();
    private static volatile TileReader PLATFORM_READER = null;
    private static volatile TileFactory PLATFORM_FACTORY = null;

    public interface TileReader {
        TileData read(DataInputStream in) throws IOException;
    }

    public interface TileFactory {
        TileData create(PlatformBlockState state, KMap<String, Object> properties);
    }

    public static synchronized TileReader bindPlatformReader(TileReader reader) {
        TileReader previous = PLATFORM_READER;
        PLATFORM_READER = Objects.requireNonNull(reader, "reader");
        return previous;
    }

    public static synchronized TileFactory bindPlatformFactory(TileFactory factory) {
        TileFactory previous = PLATFORM_FACTORY;
        PLATFORM_FACTORY = Objects.requireNonNull(factory, "factory");
        return previous;
    }

    public static synchronized void restorePlatformReader(TileReader reader) {
        PLATFORM_READER = reader;
    }

    public static synchronized void restorePlatformFactory(TileFactory factory) {
        PLATFORM_FACTORY = factory;
    }

    private static boolean detectBukkit() {
        try {
            Class.forName("org.bukkit.Bukkit", false, TileData.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @NonNull
    private Material material;
    @NonNull
    private KMap<String, Object> properties;

    public static boolean setTileState(Block block, TileData data) {
        if (block.getState() instanceof TileState && data.isApplicable(block.getBlockData()))
            return data.toBukkitTry(block);
        return false;
    }

    public static TileData getTileState(Block block, boolean useLegacy) {
        if (!BukkitPlatform.hasTile(block.getType()))
            return null;
        if (useLegacy) {
            LegacyTileData legacy = LegacyTileData.fromBukkit(block.getState());
            if (legacy != null)
                return legacy;
        }

        return new TileData().fromBukkit(block);
    }

    public static TileData of(PlatformBlockState state, KMap<String, Object> properties) {
        if (!BUKKIT_PRESENT) {
            return requirePlatformFactory(PLATFORM_FACTORY).create(state, properties);
        }
        Object handle = state.nativeHandle();
        if (!(handle instanceof BlockData blockData)) {
            return null;
        }
        return new TileData(blockData.getMaterial(), properties);
    }

    public static TileData read(DataInputStream in) throws IOException {
        if (!BUKKIT_PRESENT) {
            return requirePlatformReader(PLATFORM_READER).read(in);
        }
        if (!in.markSupported())
            throw new IOException("Mark not supported");
        in.mark(Integer.MAX_VALUE);
        try {
            return new TileData(
                    Material.matchMaterial(in.readUTF()),
                    gson.fromJson(in.readUTF(), KMap.class));
        } catch (Throwable e) {
            in.reset();
            return new LegacyTileData(in);
        } finally {
            in.mark(0);
        }
    }

    static TileFactory requirePlatformFactory(TileFactory factory) {
        if (factory == null) {
            throw new IllegalStateException("No platform tile-data factory is bound");
        }
        return factory;
    }

    static TileReader requirePlatformReader(TileReader reader) throws IOException {
        if (reader == null) {
            throw new IOException("No platform tile-data reader is bound");
        }
        return reader;
    }

    public boolean isApplicable(BlockData data) {
        return material != null && data.getMaterial() == material;
    }

    public void toBukkit(Block block) {
        if (material == null) throw new IllegalStateException("Material not set");
        if (block.getType() != material)
            throw new IllegalStateException("Material mismatch: " + block.getType() + " vs " + material);
        BukkitPlatform.deserializeTile(properties, block.getLocation());
    }

    public TileData fromBukkit(Block block) {
        if (material != null && block.getType() != material)
            throw new IllegalStateException("Material mismatch: " + block.getType() + " vs " + material);
        if (material == null) material = block.getType();
        properties = BukkitPlatform.serializeTile(block.getLocation());
        return this;
    }

    public boolean toBukkitTry(Block block) {
        try {
            //noinspection unchecked
            toBukkit(block);
            return true;
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }

        return false;
    }

    public boolean fromBukkitTry(Block block) {
        try {
            //noinspection unchecked
            fromBukkit(block);
            return true;
        } catch (Throwable e) {
            IrisLogging.reportError(e);

        }

        return false;
    }

    public void toBinary(DataOutputStream out) throws IOException {
        if (material == null) {
            out.writeUTF("");
        } else {
            NamespacedKey key = KeyedType.getKey(material);
            String value = key == null ? material.name() : key.toString();
            out.writeUTF(value);
        }
        out.writeUTF(gson.toJson(properties));
    }

    @Override
    public TileData clone() {
        TileData clone = new TileData();
        clone.material = material;
        clone.properties = properties.copy(); //TODO make a deep copy
        return clone;
    }

    @Override
    public String toString() {
        NamespacedKey key = KeyedType.getKey(material);
        String value = key == null ? String.valueOf(material) : key.toString();
        return value + gson.toJson(properties);
    }
}
