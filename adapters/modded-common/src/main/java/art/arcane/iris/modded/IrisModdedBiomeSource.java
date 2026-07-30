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
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.framework.GenerationSessionLease;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.volmlib.util.math.RNG;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

final class IrisModdedBiomeSource extends BiomeSource {
    private static final int BIOME_CACHE_MAX = 262144;
    private static final int UNRESOLVED_WARN_KEYS_MAX = 256;

    private final BiomeSource serializedSource;
    private final Set<String> warnedUnresolvedBiomeKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Holder<Biome>> visibleBiomeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Holder<Biome>> structureBiomeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Holder<Biome>> surfaceStructureBiomeCache = new ConcurrentHashMap<>();
    private final Set<StructureStateBiomeSource> structureStateSources = ConcurrentHashMap.newKeySet();
    private volatile IrisModdedChunkGenerator generator;
    private volatile Set<String> possibleStructureBiomeKeys;

    IrisModdedBiomeSource(BiomeSource serializedSource) {
        this.serializedSource = serializedSource;
    }

    void bind(IrisModdedChunkGenerator generator) {
        this.generator = generator;
    }

    void clearCaches() {
        visibleBiomeCache.clear();
        structureBiomeCache.clear();
        surfaceStructureBiomeCache.clear();
        warnedUnresolvedBiomeKeys.clear();
        possibleStructureBiomeKeys = null;
        for (StructureStateBiomeSource source : structureStateSources) {
            source.clearCache();
        }
    }

    BiomeSource forStructureState(HolderLookup<StructureSet> structureSets) {
        LinkedHashSet<Holder<Biome>> possible = new LinkedHashSet<>();
        Registry<Biome> registry = biomeRegistry();
        if (registry == null) {
            throw new IllegalStateException("Iris cannot create structure state without the biome registry");
        }
        Set<String> generatedBiomeKeys = requireConfiguredStructureBiomeKeys(exactStructureBiomeKeys());
        Set<String> missingBiomeKeys = new LinkedHashSet<>(generatedBiomeKeys);
        missingBiomeKeys.removeAll(registeredBiomeKeys(registry));
        if (!missingBiomeKeys.isEmpty()) {
            throw new IllegalStateException("Iris structure biomes are not registered: " + missingBiomeKeys);
        }
        structureSets.listElements().forEach((Holder.Reference<StructureSet> structureSet) -> {
            for (StructureSet.StructureSelectionEntry entry : structureSet.value().structures()) {
                for (Holder<Biome> biome : entry.structure().value().biomes()) {
                    String key = holderKey(biome);
                    if (isGeneratedBiomeKey(key, generatedBiomeKeys)) {
                        possible.add(biome);
                    }
                }
            }
        });
        StructureStateBiomeSource source = new StructureStateBiomeSource(this, Set.copyOf(possible));
        structureStateSources.add(source);
        return source;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        throw new UnsupportedOperationException("IrisModdedBiomeSource is serialized through IrisModdedChunkGenerator");
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        Set<String> generatedBiomeKeys = requireConfiguredStructureBiomeKeys(exactStructureBiomeKeys());
        Registry<Biome> registry = biomeRegistry();
        LinkedHashSet<Holder<Biome>> possible = new LinkedHashSet<>();
        if (registry == null) {
            for (Holder<Biome> biome : serializedSource.possibleBiomes()) {
                if (isGeneratedBiomeKey(holderKey(biome), generatedBiomeKeys)) {
                    possible.add(biome);
                }
            }
        } else {
            Set<String> missingBiomeKeys = new LinkedHashSet<>(generatedBiomeKeys);
            missingBiomeKeys.removeAll(registeredBiomeKeys(registry));
            if (!missingBiomeKeys.isEmpty()) {
                throw new IllegalStateException("Iris structure biomes are not registered: "
                        + missingBiomeKeys);
            }
            registry.listElements().forEach((Holder.Reference<Biome> reference) -> {
                if (isGeneratedBiomeKey(holderKey(reference), generatedBiomeKeys)) {
                    possible.add(reference);
                }
            });
        }
        if (possible.isEmpty()) {
            String phase = registry == null ? "serialized biome bootstrap" : "biome registry";
            throw new IllegalStateException("Iris configured structure biomes are absent from the "
                    + phase + ": " + generatedBiomeKeys);
        }
        return possible.stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        Engine engine = engineOrNull();
        if (engine == null) {
            return serializedSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
        }
        GenerationSessionLease lease = tryAcquireGenerationLease(engine, "modded_structure_biome");
        if (lease == null) {
            throw new IllegalStateException("Iris structure biome lookup was rejected during an engine transition");
        }
        try (lease; IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            if (!isReady(engine)) {
                throw new IllegalStateException("Iris structure biome lookup has no active engine runtime");
            }
            return getNoiseBiome(engine, quartX, quartY, quartZ, sampler);
        }
    }

    private Holder<Biome> getNoiseBiome(Engine engine, int quartX, int quartY, int quartZ,
                                        Climate.Sampler sampler) {
        if (isGuaranteedSurfaceBiome(quartY, engine.getMinHeight())) {
            return getSurfaceStructureBiome(engine, quartX, quartZ, sampler);
        }
        long key = packNoiseKey(quartX, quartY, quartZ);
        Holder<Biome> cached = structureBiomeCache.get(key);
        if (cached != null) {
            return cached;
        }
        Holder<Biome> resolved = resolveStructureBiome(engine, quartX, quartY, quartZ, sampler);
        Holder<Biome> existing = structureBiomeCache.putIfAbsent(key, resolved);
        if (structureBiomeCache.size() > BIOME_CACHE_MAX) {
            structureBiomeCache.clear();
        }
        return existing == null ? resolved : existing;
    }

    Holder<Biome> getVisibleNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        Engine engine = engineOrNull();
        if (engine == null) {
            return serializedSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
        }
        GenerationSessionLease lease = tryAcquireGenerationLease(engine, "modded_visible_biome");
        if (lease == null) {
            throw new IllegalStateException("Iris visible biome lookup was rejected during an engine transition");
        }
        try (lease; IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            if (!isReady(engine)) {
                throw new IllegalStateException("Iris visible biome lookup has no active engine runtime");
            }
            long key = packNoiseKey(quartX, quartY, quartZ);
            Holder<Biome> cached = visibleBiomeCache.get(key);
            if (cached != null) {
                return cached;
            }
            Holder<Biome> resolved = resolveVisibleBiome(engine, quartX, quartY, quartZ, sampler);
            Holder<Biome> existing = visibleBiomeCache.putIfAbsent(key, resolved);
            if (visibleBiomeCache.size() > BIOME_CACHE_MAX) {
                visibleBiomeCache.clear();
            }
            return existing == null ? resolved : existing;
        }
    }

    boolean isStructureReachable(Holder<Structure> structure) {
        Engine engine = engineOrNull();
        if (engine == null) {
            return isStructureReachable(structure, possibleStructureBiomeKeys());
        }
        GenerationSessionLease lease = tryAcquireGenerationLease(engine, "modded_structure_reachability");
        if (lease == null) {
            throw new IllegalStateException("Iris structure reachability was rejected during an engine transition");
        }
        try (lease; IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            return isStructureReachable(structure, possibleStructureBiomeKeys());
        }
    }

    private boolean isStructureReachable(Holder<Structure> structure, Set<String> possible) {
        for (Holder<Biome> biome : structure.value().biomes()) {
            String key = holderKey(biome);
            if (isGeneratedBiomeKey(key, possible)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<Holder<Biome>> getBiomesWithin(int x, int y, int z, int radius, Climate.Sampler sampler) {
        int minQuartY = QuartPos.fromBlock(y - radius);
        Engine engine = engineOrNull();
        if (engine == null) {
            return super.getBiomesWithin(x, y, z, radius, sampler);
        }
        GenerationSessionLease lease = tryAcquireGenerationLease(engine, "modded_biomes_within");
        if (lease == null) {
            throw new IllegalStateException("Iris biome radius lookup was rejected during an engine transition");
        }
        try (lease; IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            if (!isReady(engine)) {
                throw new IllegalStateException("Iris biome radius lookup has no active engine runtime");
            }
            boolean monumentQuery = isMonumentSurfaceBiomeQuery(
                    y, radius, engine.getMinHeight(), engine.getDimension().getFluidHeight());
            if (!monumentQuery && !isGuaranteedSurfaceBiome(minQuartY, engine.getMinHeight())) {
                return super.getBiomesWithin(x, y, z, radius, sampler);
            }
            int minQuartX = QuartPos.fromBlock(x - radius);
            int maxQuartX = QuartPos.fromBlock(x + radius);
            int minQuartZ = QuartPos.fromBlock(z - radius);
            int maxQuartZ = QuartPos.fromBlock(z + radius);
            int columns = (maxQuartX - minQuartX + 1) * (maxQuartZ - minQuartZ + 1);
            Set<Holder<Biome>> biomes = new HashSet<>(columns);
            for (int quartZ = minQuartZ; quartZ <= maxQuartZ; quartZ++) {
                for (int quartX = minQuartX; quartX <= maxQuartX; quartX++) {
                    biomes.add(getSurfaceStructureBiome(engine, quartX, quartZ, sampler));
                }
            }
            return biomes;
        }
    }

    private Holder<Biome> getSurfaceStructureBiome(Engine engine, int quartX, int quartZ,
                                                   Climate.Sampler sampler) {
        long key = packColumnKey(quartX, quartZ);
        Holder<Biome> cached = surfaceStructureBiomeCache.get(key);
        if (cached != null) {
            return cached;
        }
        Holder<Biome> resolved = resolveSurfaceStructureBiome(engine, quartX, quartZ, sampler);
        Holder<Biome> existing = surfaceStructureBiomeCache.putIfAbsent(key, resolved);
        if (surfaceStructureBiomeCache.size() > BIOME_CACHE_MAX) {
            surfaceStructureBiomeCache.clear();
        }
        return existing == null ? resolved : existing;
    }

    private Holder<Biome> resolveSurfaceStructureBiome(Engine engine, int quartX, int quartZ,
                                                       Climate.Sampler sampler) {
        Registry<Biome> registry = biomeRegistry();
        if (!isReady(engine)) {
            throw new IllegalStateException("Iris structure biome lookup ran before engine binding");
        }
        if (registry == null) {
            throw new IllegalStateException("Iris structure biome lookup has no biome registry");
        }
        IrisBiome irisBiome = engine.getComplex().getTrueBiomeStream().get(quartX << 2, quartZ << 2);
        if (irisBiome == null) {
            throw new IllegalStateException("Iris returned no surface structure biome at quart "
                    + quartX + "," + quartZ);
        }
        Holder<Biome> resolved = resolveHolder(registry, irisBiome.getStructureDerivativeKey());
        if (resolved == null) {
            throw new IllegalStateException("Iris structure biome derivative '"
                    + irisBiome.getStructureDerivativeKey() + "' is not registered at quart "
                    + quartX + "," + quartZ);
        }
        return resolved;
    }

    private Holder<Biome> resolveStructureBiome(Engine engine, int quartX, int quartY, int quartZ,
                                                Climate.Sampler sampler) {
        Registry<Biome> registry = biomeRegistry();
        BiomeResolution resolution = resolveBiomeResolution(engine, quartX, quartY, quartZ);
        if (resolution == null) {
            throw new IllegalStateException("Iris returned no structure biome at quart "
                    + quartX + "," + quartY + "," + quartZ);
        }
        if (registry == null) {
            throw new IllegalStateException("Iris structure biome lookup has no biome registry");
        }
        Holder<Biome> resolved = resolveHolder(registry, resolution.irisBiome().getStructureDerivativeKey());
        if (resolved == null) {
            throw new IllegalStateException("Iris structure biome derivative '"
                    + resolution.irisBiome().getStructureDerivativeKey() + "' is not registered at block "
                    + resolution.blockX() + "," + resolution.blockY() + "," + resolution.blockZ());
        }
        return resolved;
    }

    private Holder<Biome> resolveVisibleBiome(Engine engine, int quartX, int quartY, int quartZ,
                                              Climate.Sampler sampler) {
        Registry<Biome> registry = biomeRegistry();
        BiomeResolution resolution = resolveBiomeResolution(engine, quartX, quartY, quartZ);
        if (resolution == null || registry == null) {
            return serializedSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
        }
        String biomeKey;
        if (resolution.irisBiome().isCustom()) {
            IrisBiomeCustom customBiome = resolution.irisBiome().getCustomBiome(
                    resolution.rng(), engine, resolution.blockX(), resolution.blockY(), resolution.blockZ());
            if (customBiome == null) {
                return fallbackBiome(registry, "custom derivative of '"
                        + resolution.irisBiome().getLoadKey() + "'", quartX, quartY, quartZ, sampler);
            }
            biomeKey = ModdedWorldgenIds.biomeRef(engine, customBiome.getId());
        } else if (resolution.underground()) {
            biomeKey = resolution.irisBiome().getGroundBiomeKey(
                    resolution.rng(), engine, resolution.blockX(), resolution.blockY(), resolution.blockZ());
        } else {
            biomeKey = resolution.irisBiome().getSkyBiomeKey(
                    resolution.rng(), engine, resolution.blockX(), resolution.blockY(), resolution.blockZ());
        }
        Holder<Biome> resolved = resolveHolder(registry, biomeKey);
        return resolved == null
                ? fallbackBiome(registry, biomeKey, quartX, quartY, quartZ, sampler)
                : resolved;
    }

    private BiomeResolution resolveBiomeResolution(Engine engine, int quartX, int quartY, int quartZ) {
        if (!isReady(engine)) {
            return null;
        }
        int blockX = quartX << 2;
        int blockY = quartY << 2;
        int blockZ = quartZ << 2;
        int internalY = blockY - engine.getMinHeight();
        int caveSwitchY = Math.max(-8 - engine.getMinHeight(), 40);
        boolean underground = false;
        IrisBiome irisBiome;
        if (internalY <= caveSwitchY) {
            int surfaceY = engine.getComplex().getHeightStream().get(blockX, blockZ).intValue();
            underground = isUnderground(internalY, surfaceY);
            irisBiome = underground
                    ? engine.getCaveBiome(blockX, internalY, blockZ)
                    : engine.getComplex().getTrueBiomeStream().get(blockX, blockZ);
        } else {
            irisBiome = engine.getComplex().getTrueBiomeStream().get(blockX, blockZ);
        }
        if (irisBiome == null && underground) {
            irisBiome = engine.getComplex().getTrueBiomeStream().get(blockX, blockZ);
        }
        if (irisBiome == null) {
            return null;
        }
        IrisModdedChunkGenerator current = generator;
        long worldSeed = current == null ? engine.getWorld().getRawWorldSeed() : current.visibleBiomeSeed();
        long seed = biomeResolutionSeed(worldSeed, blockX, blockY, blockZ);
        return new BiomeResolution(irisBiome, underground, blockX, blockY, blockZ, new RNG(seed));
    }

    private Holder<Biome> resolveRequiredStructureBiome(int quartX, int quartY, int quartZ) {
        IrisModdedChunkGenerator current = generator;
        if (current == null) {
            throw new IllegalStateException("Iris structure biome source is not bound to its generator");
        }
        Engine engine = current.awaitStructureEngine();
        GenerationSessionLease lease = tryAcquireGenerationLease(engine, "modded_structure_state_biome");
        if (lease == null) {
            throw new IllegalStateException("Iris structure biome lookup was rejected during an engine transition");
        }
        try (lease; IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            if (!isReady(engine)) {
                throw new IllegalStateException("Iris structure biome lookup has no active engine runtime");
            }
            Registry<Biome> registry = biomeRegistry();
            if (registry == null) {
                throw new IllegalStateException("Iris structure biome lookup has no biome registry");
            }
            IrisBiome irisBiome;
            if (isGuaranteedSurfaceBiome(quartY, engine.getMinHeight())) {
                irisBiome = engine.getComplex().getTrueBiomeStream().get(quartX << 2, quartZ << 2);
            } else {
                BiomeResolution resolution = resolveBiomeResolution(engine, quartX, quartY, quartZ);
                irisBiome = resolution == null ? null : resolution.irisBiome();
            }
            if (irisBiome == null) {
                throw new IllegalStateException("Iris returned no structure biome at quart "
                        + quartX + "," + quartY + "," + quartZ);
            }
            String derivativeKey = irisBiome.getStructureDerivativeKey();
            Holder<Biome> resolved = resolveHolder(registry, derivativeKey);
            if (resolved == null) {
                throw new IllegalStateException("Iris structure biome derivative '" + derivativeKey
                        + "' is not registered at quart " + quartX + "," + quartY + "," + quartZ);
            }
            return resolved;
        }
    }

    static long biomeResolutionSeed(long worldSeed, int blockX, int blockY, int blockZ) {
        return worldSeed
                ^ ((long) blockX * 341873128712L)
                ^ ((long) blockY * 132897987541L)
                ^ ((long) blockZ * 42317861L);
    }

    static boolean isGeneratedBiomeKey(String key, Set<String> generatedBiomeKeys) {
        return key != null && generatedBiomeKeys.contains(key.toLowerCase(Locale.ROOT));
    }

    static Set<String> requireConfiguredStructureBiomeKeys(Set<String> configuredBiomeKeys) {
        if (configuredBiomeKeys == null || configuredBiomeKeys.isEmpty()) {
            throw new IllegalStateException("Iris has no configured structure biomes");
        }
        return configuredBiomeKeys;
    }

    static boolean isGuaranteedSurfaceBiome(int quartY, int minHeight) {
        int internalY = (quartY << 2) - minHeight;
        int caveSwitchY = Math.max(-8 - minHeight, 40);
        return internalY > caveSwitchY;
    }

    static boolean isUnderground(int internalY, int surfaceY) {
        return internalY <= surfaceY - 8;
    }

    static boolean isMonumentSurfaceBiomeQuery(int blockY, int radius, int minHeight, int fluidHeight) {
        return radius == 29 && blockY == minHeight + fluidHeight;
    }

    private static boolean isReady(Engine engine) {
        return engine != null && !engine.isClosed() && engine.getComplex() != null;
    }

    private Engine engineOrNull() {
        IrisModdedChunkGenerator current = generator;
        return current == null ? null : current.structureEngineOrNull();
    }

    private GenerationSessionLease tryAcquireGenerationLease(Engine engine, String operation) {
        if (engine == null || engine.isClosed()) {
            return null;
        }
        try {
            return engine.acquireGenerationLease(operation);
        } catch (GenerationSessionException e) {
            if (engine.isClosing() || e.isExpectedTeardown()) {
                return null;
            }
            throw new IllegalStateException("Iris biome source could not acquire generation session for "
                    + operation + ".", e);
        }
    }

    private Set<String> possibleStructureBiomeKeys() {
        Set<String> cached = possibleStructureBiomeKeys;
        if (cached != null) {
            return cached;
        }
        Set<String> possible = requireConfiguredStructureBiomeKeys(exactStructureBiomeKeys());
        Registry<Biome> registry = biomeRegistry();
        if (registry == null) {
            throw new IllegalStateException("Iris cannot resolve structure biomes without the biome registry");
        }
        Set<String> missing = new LinkedHashSet<>(possible);
        missing.removeAll(registeredBiomeKeys(registry));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Iris structure biomes are not registered: " + missing);
        }
        Set<String> resolved = Set.copyOf(possible);
        possibleStructureBiomeKeys = resolved;
        return resolved;
    }

    private Set<String> exactStructureBiomeKeys() {
        IrisModdedChunkGenerator current = generator;
        if (current == null) {
            return Set.of();
        }
        Engine engine = current.structureEngineOrNull();
        if (engine == null) {
            return current.configuredStructureBiomeKeys();
        }
        GenerationSessionLease lease = tryAcquireGenerationLease(engine, "modded_structure_biome_keys");
        if (lease == null) {
            throw new IllegalStateException("Iris structure biome key lookup was rejected during an engine transition");
        }
        try (lease; IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            if (!isReady(engine)) {
                throw new IllegalStateException("Iris structure biome key lookup has no active engine runtime");
            }
            LinkedHashSet<String> possible = new LinkedHashSet<>();
            for (IrisBiome irisBiome : engine.getAllBiomes()) {
                String derivative = normalizeKey(irisBiome.getStructureDerivativeKey());
                if (derivative != null) {
                    possible.add(derivative);
                }
                if (!irisBiome.isCustom()) {
                    continue;
                }
                for (IrisBiomeCustom customBiome : irisBiome.getCustomDerivitives()) {
                    possible.add(ModdedWorldgenIds.biomeRef(engine, customBiome.getId()));
                }
            }
            return Set.copyOf(possible);
        }
    }

    private static Set<String> registeredBiomeKeys(Registry<Biome> registry) {
        Set<String> registered = new HashSet<>();
        registry.listElements().forEach((Holder.Reference<Biome> reference) -> {
            String key = holderKey(reference);
            if (key != null) {
                registered.add(key);
            }
        });
        return registered;
    }

    private Registry<Biome> biomeRegistry() {
        MinecraftServer server = ModdedEngineBootstrap.currentServer();
        return server == null ? null : server.registryAccess().lookupOrThrow(Registries.BIOME);
    }

    private static Holder<Biome> resolveHolder(Registry<Biome> registry, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Identifier identifier = Identifier.tryParse(key);
        if (identifier == null) {
            return null;
        }
        return registry.get(identifier).<Holder<Biome>>map((Holder.Reference<Biome> reference) -> reference).orElse(null);
    }

    private static String holderKey(Holder<Biome> holder) {
        return holder.unwrapKey()
                .map((ResourceKey<Biome> key) -> key.identifier().toString().toLowerCase(Locale.ROOT))
                .orElse(null);
    }

    private static String normalizeKey(String key) {
        Identifier identifier = key == null ? null : Identifier.tryParse(key);
        return identifier == null ? null : identifier.toString().toLowerCase(Locale.ROOT);
    }

    private Holder<Biome> fallbackBiome(Registry<Biome> registry, String unresolvedKey,
                                        int quartX, int quartY, int quartZ,
                                        Climate.Sampler sampler) {
        Holder<Biome> plains = resolveHolder(registry, "minecraft:plains");
        warnUnresolvedBiome(unresolvedKey, plains == null ? "the serialized biome source" : "minecraft:plains",
                quartX, quartY, quartZ);
        return plains == null ? serializedSource.getNoiseBiome(quartX, quartY, quartZ, sampler) : plains;
    }

    private void warnUnresolvedBiome(String unresolvedKey, String fallback,
                                     int quartX, int quartY, int quartZ) {
        String key = unresolvedKey == null || unresolvedKey.isBlank() ? "<blank>" : unresolvedKey;
        if (!warnedUnresolvedBiomeKeys.add(key)) {
            return;
        }
        if (warnedUnresolvedBiomeKeys.size() > UNRESOLVED_WARN_KEYS_MAX) {
            warnedUnresolvedBiomeKeys.clear();
        }
        ModdedIrisLog.warn("Iris biome " + key + " is not registered; using " + fallback
                + " at quart " + quartX + "," + quartY + "," + quartZ
                + " (wrong biome generates; regenerate the forced datapack and restart)");
    }

    private static long packNoiseKey(int x, int y, int z) {
        return (((long) x & 67108863L) << 38)
                | (((long) z & 67108863L) << 12)
                | ((long) y & 4095L);
    }

    private static long packColumnKey(int x, int z) {
        return ((long) x << 32) ^ ((long) z & 4294967295L);
    }

    private record BiomeResolution(IrisBiome irisBiome, boolean underground, int blockX, int blockY,
                                   int blockZ, RNG rng) {
    }

    private static final class StructureStateBiomeSource extends BiomeSource {
        private final IrisModdedBiomeSource delegate;
        private final Set<Holder<Biome>> possibleBiomes;
        private final ConcurrentHashMap<Long, Holder<Biome>> resolvedBiomes = new ConcurrentHashMap<>();

        private StructureStateBiomeSource(IrisModdedBiomeSource delegate, Set<Holder<Biome>> possibleBiomes) {
            this.delegate = delegate;
            this.possibleBiomes = possibleBiomes;
        }

        private void clearCache() {
            resolvedBiomes.clear();
        }

        @Override
        protected MapCodec<? extends BiomeSource> codec() {
            throw new UnsupportedOperationException("Structure state biome sources are not serializable");
        }

        @Override
        protected Stream<Holder<Biome>> collectPossibleBiomes() {
            return possibleBiomes.stream();
        }

        @Override
        public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
            long key = packNoiseKey(x, y, z);
            Holder<Biome> cached = resolvedBiomes.get(key);
            if (cached != null) {
                return cached;
            }
            Holder<Biome> resolved = delegate.resolveRequiredStructureBiome(x, y, z);
            Holder<Biome> existing = resolvedBiomes.putIfAbsent(key, resolved);
            if (resolvedBiomes.size() > BIOME_CACHE_MAX) {
                resolvedBiomes.clear();
            }
            return existing == null ? resolved : existing;
        }

        @Override
        public Set<Holder<Biome>> getBiomesWithin(int x, int y, int z, int radius, Climate.Sampler sampler) {
            return super.getBiomesWithin(x, y, z, radius, sampler);
        }
    }
}
