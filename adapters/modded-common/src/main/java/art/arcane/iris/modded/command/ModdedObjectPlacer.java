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

package art.arcane.iris.modded.command;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IObjectPlacer;
import art.arcane.iris.engine.object.TileData;
import art.arcane.iris.modded.ModdedBlockResolution;
import art.arcane.iris.modded.ModdedBlockState;
import art.arcane.iris.modded.ModdedTileData;
import art.arcane.iris.spi.PlatformBlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

final class ModdedObjectPlacer implements IObjectPlacer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    private final ServerLevel level;
    private final Engine engine;
    private final Map<BlockPos, BlockState> undo = new HashMap<>();
    private int writes = 0;
    private int nonAirWrites = 0;
    private int skippedTiles = 0;
    private int restoredTiles = 0;

    ModdedObjectPlacer(ServerLevel level, @Nullable Engine engine) {
        this.level = level;
        this.engine = engine;
    }

    Map<BlockPos, BlockState> undoSnapshot() {
        return undo;
    }

    int writes() {
        return writes;
    }

    int nonAirWrites() {
        return nonAirWrites;
    }

    int skippedTiles() {
        return skippedTiles;
    }

    int restoredTiles() {
        return restoredTiles;
    }

    @Override
    public int getHighest(int x, int z, IrisData data) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    }

    @Override
    public int getHighest(int x, int z, IrisData data, boolean ignoreFluid) {
        return level.getHeight(ignoreFluid ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    }

    @Override
    public void set(int x, int y, int z, PlatformBlockState s) {
        if (y <= level.getMinY() || y >= level.getMinY() + level.getHeight()) {
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState current = level.getBlockState(pos);
        if (current.is(Blocks.BEDROCK)) {
            return;
        }
        BlockState target = (BlockState) s.nativeHandle();
        undo.putIfAbsent(pos, current);
        level.setBlock(pos, target, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        writes++;
        if (!target.isAir()) {
            nonAirWrites++;
        }
    }

    @Override
    public PlatformBlockState get(int x, int y, int z) {
        return ModdedBlockState.of(level.getBlockState(new BlockPos(x, y, z)), null);
    }

    @Override
    public boolean isPreventingDecay() {
        return false;
    }

    @Override
    public boolean isCarved(int x, int y, int z) {
        return false;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return ModdedBlockResolution.isSolid(level.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public boolean isUnderwater(int x, int z) {
        return false;
    }

    @Override
    public int getFluidHeight() {
        return 63;
    }

    @Override
    public boolean isDebugSmartBore() {
        return false;
    }

    @Override
    public void setTile(int xx, int yy, int zz, TileData tile) {
        if (!(tile instanceof ModdedTileData moddedTile)) {
            skippedTiles++;
            return;
        }
        BlockPos pos = new BlockPos(xx, yy, zz);
        BlockState state = level.getBlockState(pos);
        BlockState adjusted = moddedTile.adjustBlockState(state);
        if (adjusted != state) {
            level.setBlock(pos, adjusted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            state = adjusted;
        }
        if (!state.hasBlockEntity()) {
            skippedTiles++;
            return;
        }
        try {
            BlockEntity restored = level.getBlockEntity(pos);
            if (restored == null && state.getBlock() instanceof EntityBlock entityBlock) {
                restored = entityBlock.newBlockEntity(pos, state);
            }
            if (restored == null) {
                skippedTiles++;
                return;
            }
            level.setBlockEntity(restored);
            if (!moddedTile.apply(restored, level)) {
                skippedTiles++;
                return;
            }
            restoredTiles++;
        } catch (Throwable e) {
            LOGGER.error("Iris tile restore failed at {} {} {}", xx, yy, zz, e);
            skippedTiles++;
        }
    }

    @Override
    public <T> void setData(int xx, int yy, int zz, T data) {
    }

    @Override
    public <T> T getData(int xx, int yy, int zz, Class<T> t) {
        return null;
    }

    @Override
    public Engine getEngine() {
        return engine;
    }
}
