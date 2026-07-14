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

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.PlacedObject;
import art.arcane.iris.engine.object.IObjectLoot;
import art.arcane.iris.engine.object.InventorySlotType;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisLootMode;
import art.arcane.iris.engine.object.IrisLootReference;
import art.arcane.iris.engine.object.IrisLootTable;
import art.arcane.iris.engine.object.IrisObjectLoot;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisObjectVanillaLoot;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.matter.Matter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class ModdedLootApplier {
    private ModdedLootApplier() {
    }

    public static void apply(Engine engine, ServerLevel level, BlockPos pos, BlockState state, MantleChunk<Matter> mc, RNG rng, int localX, int localZ) {
        List<LootSource> sources = resolveSources(engine, level, rng, pos, state, mc);
        if (sources.isEmpty()) {
            return;
        }

        Container container = resolveContainer(level, pos, state);
        if (container == null) {
            IrisLogging.debug("Iris loot: no container at " + pos);
            return;
        }

        KList<ItemStack> items = new KList<>();
        LootParams nativeParams = null;
        for (LootSource source : sources) {
            if (source instanceof IrisLootSource irisSource) {
                IrisLootTable table = irisSource.table();
                if (table == null) {
                    continue;
                }
                items.addAll(ModdedItemTranslator.loot(table, rng, InventorySlotType.STORAGE, level, localX, pos.getY(), localZ));
                continue;
            }
            if (source instanceof NativeLootSource nativeSource) {
                if (nativeParams == null) {
                    nativeParams = new LootParams.Builder(level)
                            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                            .create(LootContextParamSets.CHEST);
                }
                items.addAll(nativeSource.table().getRandomItems(nativeParams, rng.nextLong()));
            }
        }

        fillContainer(container, items, rng);
    }

    private static List<LootSource> resolveSources(Engine engine, ServerLevel level, RNG rng, BlockPos pos, BlockState state, MantleChunk<Matter> mc) {
        int rx = pos.getX();
        int rz = pos.getZ();
        int ry = pos.getY() - engine.getWorld().minHeight();
        double he = engine.getComplex().getHeightStream().get(rx, rz);
        List<LootSource> sources = new ArrayList<>();

        PlacedObject po = engine.getObjectPlacement(rx, ry, rz, mc);
        if (po != null && po.getPlacement() != null && ModdedBlockResolution.isStorageChest(state)) {
            Candidate picked = pickPlacementTable(engine, level, po.getPlacement(), state, rng);
            if (picked != null) {
                sources.add(picked.source());
                if (po.getPlacement().isOverrideGlobalLoot()) {
                    return sources;
                }
            }
        }

        IrisRegion region = engine.getComplex().getRegionStream().get(rx, rz);
        IrisBiome biomeSurface = engine.getComplex().getTrueBiomeStream().get(rx, rz);
        IrisBiome biomeUnder = ry < he ? engine.getCaveBiome(rx, ry, rz) : biomeSurface;

        double multiplier = 1D * engine.getDimension().getLoot().getMultiplier() * region.getLoot().getMultiplier() * biomeSurface.getLoot().getMultiplier() * biomeUnder.getLoot().getMultiplier();
        boolean fallback = sources.isEmpty();
        injectReferenceSources(sources, engine.getDimension().getLoot(), engine, fallback);
        injectReferenceSources(sources, region.getLoot(), engine, fallback);
        injectReferenceSources(sources, biomeSurface.getLoot(), engine, fallback);
        injectReferenceSources(sources, biomeUnder.getLoot(), engine, fallback);
        scaleSources(sources, multiplier, rng);
        return sources;
    }

    private static Candidate pickPlacementTable(Engine engine, ServerLevel level, IrisObjectPlacement placement, BlockState state, RNG rng) {
        List<Candidate> exact = new ArrayList<>();
        List<Candidate> basic = new ArrayList<>();
        List<Candidate> global = new ArrayList<>();

        for (IrisObjectLoot loot : placement.getLoot()) {
            if (loot == null) {
                continue;
            }
            IrisLootTable table = engine.getData().getLootLoader().load(loot.getName());
            if (table == null) {
                IrisLogging.warn("Couldn't find loot table " + loot.getName());
                continue;
            }
            bucket(engine, loot, new Candidate(new IrisLootSource(table), loot.getWeight()), state, exact, basic, global);
        }

        for (IrisObjectVanillaLoot loot : placement.getVanillaLoot()) {
            if (loot == null) {
                continue;
            }
            ResourceKey<LootTable> key = resolveNativeKey(loot.getName(), candidate -> hasNativeTable(level, candidate));
            if (key == null) {
                IrisLogging.warn("Couldn't find vanilla loot table " + loot.getName());
                continue;
            }
            LootTable table = level.getServer().reloadableRegistries().lookup()
                    .lookupOrThrow(Registries.LOOT_TABLE)
                    .getOrThrow(key)
                    .value();
            bucket(engine, loot, new Candidate(new NativeLootSource(table), loot.getWeight()), state, exact, basic, global);
        }

        List<Candidate> pool = !exact.isEmpty() ? exact : !basic.isEmpty() ? basic : global;
        return pickWeighted(pool, rng);
    }

    static <T> void injectSources(List<T> sources, List<? extends T> additions, IrisLootMode mode, boolean fallback) {
        if (mode == IrisLootMode.FALLBACK && !fallback) {
            return;
        }
        if (mode == IrisLootMode.CLEAR || mode == IrisLootMode.REPLACE) {
            sources.clear();
        }
        sources.addAll(additions);
    }

    static <T> void scaleSources(List<T> sources, double multiplier, RNG rng) {
        if (sources.isEmpty()) {
            return;
        }
        int target = (int) Math.round(sources.size() * multiplier);
        while (sources.size() < target && !sources.isEmpty()) {
            sources.add(sources.get(rng.i(sources.size() - 1)));
        }
        while (sources.size() > target && !sources.isEmpty()) {
            sources.remove(rng.i(sources.size() - 1));
        }
    }

    static ResourceKey<LootTable> resolveNativeKey(String name, Predicate<ResourceKey<LootTable>> registryContains) {
        Identifier id = name == null ? null : Identifier.tryParse(name);
        if (id == null) {
            return null;
        }
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, id);
        return registryContains.test(key) ? key : null;
    }

    static void fillContainer(Container container, List<ItemStack> items, RNG rng) {
        for (ItemStack item : items) {
            addItem(container, item);
        }
        scramble(container, rng);
        container.setChanged();
    }

    private static void injectReferenceSources(List<LootSource> sources, IrisLootReference reference, Engine engine, boolean fallback) {
        IrisLootMode mode = reference.getMode();
        if (mode == IrisLootMode.FALLBACK && !fallback) {
            return;
        }
        injectSources(sources, irisSources(reference, engine), mode, fallback);
    }

    private static List<LootSource> irisSources(IrisLootReference reference, Engine engine) {
        KList<IrisLootTable> tables = reference.getLootTables(engine.getComplex());
        List<LootSource> sources = new ArrayList<>(tables.size());
        for (IrisLootTable table : tables) {
            sources.add(new IrisLootSource(table));
        }
        return sources;
    }

    private static boolean hasNativeTable(ServerLevel level, ResourceKey<LootTable> key) {
        return level.getServer().reloadableRegistries().lookup()
                .lookup(Registries.LOOT_TABLE)
                .flatMap(registry -> registry.get(key))
                .isPresent();
    }

    private static void bucket(Engine engine, IObjectLoot loot, Candidate candidate, BlockState state, List<Candidate> exact, List<Candidate> basic, List<Candidate> global) {
        if (loot.getFilter().isEmpty()) {
            global.add(candidate);
            return;
        }

        for (PlatformBlockState filterState : loot.getFilter(engine.getData())) {
            BlockState filter = (BlockState) filterState.nativeHandle();
            if (loot.isExact() ? filter == state : filter.getBlock() == state.getBlock()) {
                (loot.isExact() ? exact : basic).add(candidate);
                return;
            }
        }
    }

    private static Candidate pickWeighted(List<Candidate> pool, RNG rng) {
        if (pool.isEmpty()) {
            return null;
        }
        int total = 0;
        for (Candidate candidate : pool) {
            total += Math.max(0, candidate.weight());
        }
        if (total <= 0) {
            return pool.get(rng.nextInt(pool.size()));
        }
        int pull = rng.nextInt(total);
        for (Candidate candidate : pool) {
            pull -= Math.max(0, candidate.weight());
            if (pull < 0) {
                return candidate;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private static Container resolveContainer(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container combined = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (combined != null) {
                return combined;
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    private static void addItem(Container container, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()) {
                container.setItem(i, stack);
                return;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack) && existing.getCount() < existing.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(move);
                stack.shrink(move);
            }
        }
    }

    private static void scramble(Container container, RNG rng) {
        int size = container.getContainerSize();
        ItemStack[] items = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            items[i] = container.getItem(i);
        }
        boolean packedFull = false;

        splitting:
        for (int i = 0; i < items.length; i++) {
            ItemStack is = items[i];

            if (!is.isEmpty() && is.getCount() > 1 && !packedFull) {
                for (int j = 0; j < items.length; j++) {
                    if (items[j].isEmpty()) {
                        int take = rng.nextInt(is.getCount());
                        take = take == 0 ? 1 : take;
                        is.setCount(is.getCount() - take);
                        items[j] = is.copyWithCount(take);
                        continue splitting;
                    }
                }

                packedFull = true;
            }
        }

        for (int i = items.length; i > 1; i--) {
            int j = rng.nextInt(i);
            ItemStack tmp = items[i - 1];
            items[i - 1] = items[j];
            items[j] = tmp;
        }

        for (int i = 0; i < size; i++) {
            container.setItem(i, items[i]);
        }
    }

    private interface LootSource {
    }

    private record IrisLootSource(IrisLootTable table) implements LootSource {
    }

    private record NativeLootSource(LootTable table) implements LootSource {
    }

    private record Candidate(LootSource source, int weight) {
    }
}
