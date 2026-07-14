package art.arcane.iris.core.nms.v26_2_R1;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class VanillaStructureBiomes {
    private VanillaStructureBiomes() {
    }

    static Set<String> possibleBiomeKeys(BiomeSource source) {
        Set<String> keys = new LinkedHashSet<>();
        if (source == null) {
            return keys;
        }
        Set<Holder<Biome>> possibleBiomes = source instanceof CustomBiomeSource customBiomeSource
                ? customBiomeSource.possibleStructureBiomes()
                : source.possibleBiomes();
        for (Holder<Biome> holder : possibleBiomes) {
            Optional<ResourceKey<Biome>> key = holder.unwrapKey();
            if (key.isPresent()) {
                keys.add(key.get().identifier().toString());
            }
        }
        return keys;
    }

    static Set<String> structureBiomeKeys(RegistryAccess access, String structureKey) {
        Set<String> keys = new LinkedHashSet<>();
        if (access == null || structureKey == null || structureKey.isEmpty()) {
            return keys;
        }
        Registry<Structure> registry = access.lookupOrThrow(Registries.STRUCTURE);
        Structure structure = registry.getValue(Identifier.parse(structureKey));
        if (structure == null) {
            return keys;
        }
        for (Holder<Biome> holder : structure.biomes()) {
            Optional<ResourceKey<Biome>> key = holder.unwrapKey();
            if (key.isPresent()) {
                keys.add(key.get().identifier().toString());
            }
        }
        return keys;
    }

    static Set<String> reachableStructureKeys(ServerLevel level, BiomeSource source) {
        Set<String> reachable = new LinkedHashSet<>();
        if (level == null || source == null) {
            return reachable;
        }
        Set<String> possible = possibleBiomeKeys(source);
        if (possible.isEmpty()) {
            return reachable;
        }
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (Map.Entry<ResourceKey<Structure>, Structure> entry : registry.entrySet()) {
            for (Holder<Biome> holder : entry.getValue().biomes()) {
                Optional<ResourceKey<Biome>> key = holder.unwrapKey();
                if (key.isPresent() && possible.contains(key.get().identifier().toString())) {
                    reachable.add(entry.getKey().identifier().toString());
                    break;
                }
            }
        }
        return reachable;
    }
}
