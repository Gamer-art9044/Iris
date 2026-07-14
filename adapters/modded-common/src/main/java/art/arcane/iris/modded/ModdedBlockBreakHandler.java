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

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBlockData;
import art.arcane.iris.engine.object.IrisBlockDrops;
import art.arcane.iris.engine.object.IrisLoot;
import art.arcane.iris.engine.object.IrisMarker;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.MatterMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public final class ModdedBlockBreakHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final ConcurrentHashMap<BreakKey, PendingBreak> PENDING = new ConcurrentHashMap<>();

    private ModdedBlockBreakHandler() {
    }

    public static void prepare(ServerLevel level, BlockPos position, BlockState brokenState) {
        if (engineFor(level) == null) {
            return;
        }
        BreakKey key = new BreakKey(level, position.asLong());
        PendingBreak pending = new PendingBreak(brokenState);
        PENDING.put(key, pending);
        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler != null) {
            scheduler.laterGlobal(() -> finishPending(key, pending, position), 1);
        }
    }

    public static void clear() {
        PENDING.clear();
    }

    public static Result complete(ServerLevel level, BlockPos position, BlockState fallbackState) {
        BreakKey key = new BreakKey(level, position.asLong());
        PendingBreak pending = PENDING.remove(key);
        BlockState brokenState = pending == null ? fallbackState : pending.brokenState();
        return evaluateSafely(level, position, brokenState);
    }

    public static Result completePrepared(ServerLevel level, BlockPos position) {
        BreakKey key = new BreakKey(level, position.asLong());
        PendingBreak pending = PENDING.get(key);
        if (pending == null || level.getBlockState(position).equals(pending.brokenState())) {
            return null;
        }
        return PENDING.remove(key, pending) ? evaluateSafely(level, position, pending.brokenState()) : null;
    }

    public static void spawn(ServerLevel level, BlockPos position, KList<ItemStack> drops) {
        for (ItemStack stack : drops) {
            ItemEntity item = createDrop(level, position, stack);
            if (item != null) {
                level.addFreshEntity(item);
            }
        }
    }

    public static ItemEntity createDrop(ServerLevel level, BlockPos position, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        RandomSource random = level.getRandom();
        double x = position.getX() + 0.5D + Mth.nextDouble(random, -0.25D, 0.25D);
        double y = position.getY() + 0.5D + Mth.nextDouble(random, -0.25D, 0.25D) - EntityTypes.ITEM.getHeight() / 2D;
        double z = position.getZ() + 0.5D + Mth.nextDouble(random, -0.25D, 0.25D);
        ItemEntity item = new ItemEntity(level, x, y, z, stack);
        item.setDefaultPickUpDelay();
        return item;
    }

    private static void finishPending(BreakKey key, PendingBreak pending, BlockPos position) {
        if (!PENDING.remove(key, pending)) {
            return;
        }
        ServerLevel level = key.level();
        if (level.getBlockState(position).equals(pending.brokenState())) {
            return;
        }
        Result result = evaluateSafely(level, position, pending.brokenState());
        spawn(level, position, result.drops());
    }

    private static Result evaluateSafely(ServerLevel level, BlockPos position, BlockState brokenState) {
        try {
            return evaluate(level, position, brokenState);
        } catch (Throwable error) {
            LOGGER.error("Iris block-break processing failed at {},{},{} in {}", position.getX(), position.getY(), position.getZ(),
                    level.dimension().identifier(), error);
            return Result.empty();
        }
    }

    private static Result evaluate(ServerLevel level, BlockPos position, BlockState brokenState) {
        Engine engine = engineFor(level);
        if (engine == null || engine.isClosed()) {
            return Result.empty();
        }

        removeMarker(engine, position);
        KList<IrisBlockDrops> providers = providers(engine, position, brokenState);
        if (providers.isEmpty()) {
            return Result.empty();
        }

        KList<ItemStack> drops = new KList<>();
        boolean replaceVanillaDrops = false;
        for (IrisBlockDrops provider : providers) {
            replaceVanillaDrops |= provider.isReplaceVanillaDrops();
            for (IrisLoot loot : provider.getDrops()) {
                if (RNG.r.i(1, loot.getRarity()) != loot.getRarity()) {
                    continue;
                }
                ItemStack stack = ModdedItemTranslator.stack(loot, RNG.r, level);
                if (stack != null && !stack.isEmpty()) {
                    drops.add(stack);
                }
            }
        }
        return new Result(drops, replaceVanillaDrops);
    }

    private static void removeMarker(Engine engine, BlockPos position) {
        int mantleY = position.getY() - engine.getMinHeight();
        MatterMarker marker = engine.getMantle().getMantle().get(position.getX(), mantleY, position.getZ(), MatterMarker.class);
        if (marker == null) {
            return;
        }
        String tag = marker.getTag();
        if ("cave_floor".equals(tag) || "cave_ceiling".equals(tag)) {
            return;
        }
        IrisMarker configured = engine.getData().getMarkerLoader().load(tag);
        if (configured == null || configured.isRemoveOnChange()) {
            engine.getMantle().getMantle().remove(position.getX(), mantleY, position.getZ(), MatterMarker.class);
        }
    }

    private static KList<IrisBlockDrops> providers(Engine engine, BlockPos position, BlockState brokenState) {
        KList<IrisBlockDrops> providers = new KList<>();
        IrisData data = engine.getData();
        int relativeY = position.getY() - engine.getMinHeight();
        IrisBiome biome = engine.getBiome(position.getX(), relativeY, position.getZ());
        if (biome != null) {
            addMatching(providers, biome.getBlockDrops(), brokenState, data);
        }
        if (skipsParents(providers)) {
            return providers;
        }
        IrisRegion region = engine.getRegion(position.getX(), position.getZ());
        if (region != null) {
            addMatching(providers, region.getBlockDrops(), brokenState, data);
        }
        addMatching(providers, engine.getDimension().getBlockDrops(), brokenState, data);
        return providers;
    }

    private static void addMatching(KList<IrisBlockDrops> matches, KList<IrisBlockDrops> candidates, BlockState brokenState, IrisData data) {
        for (IrisBlockDrops candidate : candidates) {
            if (matches(candidate, brokenState, data)) {
                matches.add(candidate);
            }
        }
    }

    private static boolean skipsParents(KList<IrisBlockDrops> providers) {
        for (IrisBlockDrops provider : providers) {
            if (provider.isSkipParents()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(IrisBlockDrops provider, BlockState brokenState, IrisData data) {
        for (IrisBlockData configuredBlock : provider.getBlocks()) {
            PlatformBlockState resolved = configuredBlock.getBlockData(data);
            if (resolved == null || !(resolved.nativeHandle() instanceof BlockState configuredState)) {
                continue;
            }
            if (matchesState(configuredState, brokenState, provider.isExactBlocks())) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesState(BlockState configuredState, BlockState brokenState, boolean exact) {
        return exact ? configuredState.equals(brokenState) : configuredState.getBlock() == brokenState.getBlock();
    }

    private static Engine engineFor(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof IrisModdedChunkGenerator irisGenerator)) {
            return null;
        }
        Engine engine = irisGenerator.engineIfBound();
        if (engine != null) {
            return engine;
        }
        try {
            return irisGenerator.commandEngine();
        } catch (Throwable error) {
            LOGGER.error("Iris could not resolve the engine for a block break in {}", level.dimension().identifier(), error);
            return null;
        }
    }

    public record Result(KList<ItemStack> drops, boolean replaceVanillaDrops) {
        private static Result empty() {
            return new Result(new KList<>(), false);
        }
    }

    private record BreakKey(ServerLevel level, long position) {
    }

    private record PendingBreak(BlockState brokenState) {
    }
}
