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

package art.arcane.iris.modded;

import art.arcane.iris.engine.object.TileData;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.collection.KMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.TagValueInput;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class ModdedTileData extends TileData {
    public static final String NBT_PROPERTY = "nbt";
    static final String LEGACY_BANNER_COLOR_PROPERTY = "iris:legacy_banner_color";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.LENIENT).create();

    private final byte[] raw;
    private final KMap<String, Object> tileProperties;
    private final String expectedBlockKey;
    private final int legacyType;

    ModdedTileData(byte[] raw, KMap<String, Object> tileProperties, String expectedBlockKey, int legacyType) {
        super();
        this.raw = raw;
        this.tileProperties = tileProperties == null ? new KMap<>() : tileProperties;
        this.expectedBlockKey = expectedBlockKey;
        this.legacyType = legacyType;
    }

    public static ModdedTileData capture(String blockKey, String snbt) throws IOException {
        KMap<String, Object> properties = new KMap<>();
        properties.put(NBT_PROPERTY, snbt);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(blockKey);
            out.writeUTF(GSON.toJson(properties));
        }
        return new ModdedTileData(bytes.toByteArray(), properties, normalizeBlockKey(blockKey), -1);
    }

    public static ModdedTileData fromProperties(PlatformBlockState state, KMap<String, Object> properties) {
        String blockKey = state.placementBaseState().key();
        int bracket = blockKey.indexOf('[');
        if (bracket >= 0) {
            blockKey = blockKey.substring(0, bracket);
        }
        KMap<String, Object> copied = properties == null ? new KMap<>() : properties.copy();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(blockKey);
                out.writeUTF(GSON.toJson(copied));
            }
            return new ModdedTileData(bytes.toByteArray(), copied, normalizeBlockKey(blockKey), -1);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode modded tile data for " + blockKey, e);
        }
    }

    public String snbt() {
        Object value = tileProperties.get(NBT_PROPERTY);
        return value == null ? null : value.toString();
    }

    public boolean apply(BlockEntity blockEntity, ServerLevel level) throws Exception {
        CompoundTag payload = payload();
        if (payload == null) {
            return false;
        }
        CompoundTag merged = blockEntity.saveWithoutMetadata(level.registryAccess());
        merged.merge(payload);
        blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), merged));
        blockEntity.setChanged();
        level.sendBlockUpdated(blockEntity.getBlockPos(), blockEntity.getBlockState(), blockEntity.getBlockState(), Block.UPDATE_CLIENTS);
        return true;
    }

    public boolean isApplicable(BlockState state, BlockEntity blockEntity) {
        if (expectedBlockKey != null) {
            return expectedBlockKey.equals(normalizeBlockKey(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        }
        return switch (legacyType) {
            case 0 -> blockEntity instanceof SignBlockEntity;
            case 1 -> blockEntity instanceof SpawnerBlockEntity;
            case 2 -> blockEntity instanceof BannerBlockEntity;
            case 3 -> blockEntity instanceof RandomizableContainerBlockEntity;
            default -> false;
        };
    }

    CompoundTag payload() throws Exception {
        String snbt = snbt();
        if (snbt != null && !snbt.isBlank()) {
            return NbtUtils.snbtToStructure(snbt);
        }
        Tag converted = toTag(tileProperties);
        return converted instanceof CompoundTag compound ? compound : null;
    }

    public BlockState adjustBlockState(BlockState state) {
        Object colorValue = tileProperties.get(LEGACY_BANNER_COLOR_PROPERTY);
        if (colorValue == null) {
            return state;
        }
        Identifier currentId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String currentPath = currentId.getPath();
        if (!currentPath.endsWith("_banner")) {
            return state;
        }
        String suffix = currentPath.endsWith("_wall_banner") ? "_wall_banner" : "_banner";
        Identifier targetId = Identifier.tryParse("minecraft:" + colorValue + suffix);
        if (targetId == null || !BuiltInRegistries.BLOCK.containsKey(targetId)) {
            return state;
        }
        BlockState adjusted = BuiltInRegistries.BLOCK.getValue(targetId).defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            if (adjusted.hasProperty(property)) {
                adjusted = copyProperty(adjusted, state, property);
            }
        }
        return adjusted;
    }

    private static Tag toTag(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            CompoundTag compound = new CompoundTag();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (LEGACY_BANNER_COLOR_PROPERTY.equals(String.valueOf(entry.getKey()))) {
                    continue;
                }
                Tag child = toTag(entry.getValue());
                if (child != null) {
                    compound.put(String.valueOf(entry.getKey()), child);
                }
            }
            return compound;
        }
        if (value instanceof List<?> values) {
            ListTag list = new ListTag();
            for (Object entry : values) {
                Tag child = toTag(entry);
                if (child != null) {
                    list.add(child);
                }
            }
            return list;
        }
        if (value instanceof Boolean bool) {
            return ByteTag.valueOf(bool);
        }
        if (value instanceof Byte number) {
            return ByteTag.valueOf(number);
        }
        if (value instanceof Short number) {
            return ShortTag.valueOf(number);
        }
        if (value instanceof Integer number) {
            return IntTag.valueOf(number);
        }
        if (value instanceof Long number) {
            return LongTag.valueOf(number);
        }
        if (value instanceof Float number) {
            return FloatTag.valueOf(number);
        }
        if (value instanceof Number number) {
            return DoubleTag.valueOf(number.doubleValue());
        }
        return StringTag.valueOf(String.valueOf(value));
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState target, BlockState source, Property<T> property) {
        return target.setValue(property, source.getValue(property));
    }

    static String normalizeBlockKey(String blockKey) {
        if (blockKey == null || blockKey.isBlank()) {
            return null;
        }
        String normalized = blockKey.trim().toLowerCase(java.util.Locale.ROOT);
        int bracket = normalized.indexOf('[');
        if (bracket >= 0) {
            normalized = normalized.substring(0, bracket);
        }
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized.replaceAll("\\s+", "_");
        }
        Identifier identifier = Identifier.tryParse(normalized);
        return identifier == null ? null : identifier.toString();
    }

    @Override
    public KMap<String, Object> getProperties() {
        return tileProperties;
    }

    @Override
    public void toBinary(DataOutputStream out) throws IOException {
        out.write(raw);
    }

    @Override
    public TileData clone() {
        return this;
    }
}
