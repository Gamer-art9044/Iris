package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.core.datapack.DatapackStructureScopeIndex;
import art.arcane.iris.engine.framework.NativeStructureFrequencyScale;
import art.arcane.iris.engine.object.IrisImportedStructureControl;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class DatapackStructureStateFilter {
    private DatapackStructureStateFilter() {
    }

    static Selection filter(
            List<Holder<StructureSet>> structureSets,
            DatapackStructureScopeIndex scopeIndex,
            Set<String> declaredSources
    ) {
        return filter(structureSets, scopeIndex, declaredSources, new IrisImportedStructureControl());
    }

    static Selection filter(
            List<Holder<StructureSet>> structureSets,
            DatapackStructureScopeIndex scopeIndex,
            Set<String> declaredSources,
            IrisImportedStructureControl importedStructures
    ) {
        if (scopeIndex.isEmpty()) {
            return new Selection(
                    scaleFrequencyOverrides(structureSets, importedStructures),
                    0,
                    0);
        }
        Map<String, Holder<StructureSet>> holdersByKey = new HashMap<>();
        for (Holder<StructureSet> holder : structureSets) {
            String key = structureSetKey(holder);
            if (key != null) {
                holdersByKey.putIfAbsent(key, holder);
            }
        }
        Map<Holder<StructureSet>, Holder<StructureSet>> scopedByIdentity = new IdentityHashMap<>();
        Set<Holder<StructureSet>> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Holder<StructureSet>> filteredSets = new ArrayList<>(structureSets.size());
        int retainedManagedSets = 0;
        int excludedManagedSets = 0;
        for (Holder<StructureSet> holder : structureSets) {
            String setKey = structureSetKey(holder);
            boolean managedSet = setKey != null && scopeIndex.isManagedStructureSet(setKey);
            Holder<StructureSet> scopedHolder = scopeHolder(
                    holder,
                    scopeIndex,
                    declaredSources,
                    holdersByKey,
                    scopedByIdentity,
                    visiting);
            if (scopedHolder == null) {
                if (managedSet) {
                    excludedManagedSets++;
                }
                continue;
            }
            if (managedSet) {
                retainedManagedSets++;
            }
            filteredSets.add(scopedHolder);
        }
        return new Selection(
                scaleFrequencyOverrides(List.copyOf(filteredSets), importedStructures),
                retainedManagedSets,
                excludedManagedSets);
    }

    static String structureSetKey(Holder<StructureSet> holder) {
        Optional<ResourceKey<StructureSet>> key = holder.unwrapKey();
        if (key.isPresent()) {
            return key.get().identifier().toString();
        }
        if (holder.value().placement()
                instanceof ChunkGeneratorStructureState.KeyedRandomSpreadStructurePlacement keyedPlacement) {
            return keyedPlacement.key.identifier().toString();
        }
        return null;
    }

    private static List<StructureSet.StructureSelectionEntry> filterEntries(
            List<StructureSet.StructureSelectionEntry> entries,
            DatapackStructureScopeIndex scopeIndex,
            Set<String> declaredSources
    ) {
        List<StructureSet.StructureSelectionEntry> filtered = new ArrayList<>(entries.size());
        for (StructureSet.StructureSelectionEntry entry : entries) {
            String structureKey = entry.structure().unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse(null);
            if (structureKey == null || scopeIndex.allowsStructure(structureKey, declaredSources)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private static Holder<StructureSet> scopeHolder(
            Holder<StructureSet> holder,
            DatapackStructureScopeIndex scopeIndex,
            Set<String> declaredSources,
            Map<String, Holder<StructureSet>> holdersByKey,
            Map<Holder<StructureSet>, Holder<StructureSet>> scopedByIdentity,
            Set<Holder<StructureSet>> visiting
    ) {
        if (scopedByIdentity.containsKey(holder)) {
            return scopedByIdentity.get(holder);
        }
        if (!visiting.add(holder)) {
            return null;
        }
        try {
            String setKey = structureSetKey(holder);
            if (setKey != null && scopeIndex.isManagedStructureSet(setKey)
                    && !scopeIndex.allowsStructureSet(setKey, declaredSources)) {
                scopedByIdentity.put(holder, null);
                return null;
            }
            StructureSet originalSet = holder.value();
            List<StructureSet.StructureSelectionEntry> entries = filterEntries(
                    originalSet.structures(), scopeIndex, declaredSources);
            if (entries.isEmpty()) {
                scopedByIdentity.put(holder, null);
                return null;
            }
            StructurePlacement placement = scopePlacement(
                    originalSet.placement(),
                    scopeIndex,
                    declaredSources,
                    holdersByKey,
                    scopedByIdentity,
                    visiting);
            if (entries.size() == originalSet.structures().size()
                    && placement == originalSet.placement()) {
                scopedByIdentity.put(holder, holder);
                return holder;
            }
            Holder<StructureSet> scoped = replacementHolder(
                    holder, new StructureSet(entries, placement));
            scopedByIdentity.put(holder, scoped);
            return scoped;
        } finally {
            visiting.remove(holder);
        }
    }

    static Optional<StructurePlacement.ExclusionZone> exclusionZone(StructurePlacement placement) {
        Object value = declaredFieldValue(StructurePlacement.class, placement, Optional.class);
        if (value instanceof Optional<?> optional && (optional.isEmpty()
                || optional.get() instanceof StructurePlacement.ExclusionZone)) {
            @SuppressWarnings("unchecked")
            Optional<StructurePlacement.ExclusionZone> resolved =
                    (Optional<StructurePlacement.ExclusionZone>) optional;
            return resolved;
        }
        throw new IllegalStateException("Could not read structure placement exclusion zone from "
                + placement.getClass().getName());
    }

    private static StructurePlacement scopePlacement(
            StructurePlacement placement,
            DatapackStructureScopeIndex scopeIndex,
            Set<String> declaredSources,
            Map<String, Holder<StructureSet>> holdersByKey,
            Map<Holder<StructureSet>, Holder<StructureSet>> scopedByIdentity,
            Set<Holder<StructureSet>> visiting
    ) {
        Optional<StructurePlacement.ExclusionZone> currentZone = exclusionZone(placement);
        if (currentZone.isEmpty()) {
            return placement;
        }

        Holder<StructureSet> target = currentZone.get().otherSet();
        String targetKey = structureSetKey(target);
        if (targetKey != null) {
            target = holdersByKey.getOrDefault(targetKey, target);
        }
        Holder<StructureSet> scopedTarget = scopeHolder(
                target,
                scopeIndex,
                declaredSources,
                holdersByKey,
                scopedByIdentity,
                visiting);
        Optional<StructurePlacement.ExclusionZone> scopedZone = Optional.empty();
        if (scopedTarget != null) {
            scopedZone = Optional.of(new StructurePlacement.ExclusionZone(
                    scopedTarget,
                    currentZone.get().chunkCount()));
        }
        if (scopedTarget == currentZone.get().otherSet()) {
            return placement;
        }
        return copyPlacement(placement, scopedZone, 1D);
    }

    private static List<Holder<StructureSet>> scaleFrequencyOverrides(
            List<Holder<StructureSet>> structureSets,
            IrisImportedStructureControl importedStructures
    ) {
        if (!importedStructures.hasFrequencyOverrides()) {
            return structureSets;
        }
        Map<String, Holder<StructureSet>> holdersByKey = new HashMap<>();
        for (Holder<StructureSet> holder : structureSets) {
            String key = structureSetKey(holder);
            if (key != null) {
                holdersByKey.putIfAbsent(key, holder);
            }
        }
        Map<Holder<StructureSet>, Holder<StructureSet>> scaledByIdentity = new IdentityHashMap<>();
        List<Holder<StructureSet>> scaledSets = new ArrayList<>(structureSets.size());
        boolean changed = false;
        for (Holder<StructureSet> holder : structureSets) {
            Holder<StructureSet> scaled = scaleFrequencyHolder(
                    holder, importedStructures, holdersByKey, scaledByIdentity);
            scaledSets.add(scaled);
            changed |= scaled != holder;
        }
        return changed ? List.copyOf(scaledSets) : structureSets;
    }

    private static Holder<StructureSet> scaleFrequencyHolder(
            Holder<StructureSet> holder,
            IrisImportedStructureControl importedStructures,
            Map<String, Holder<StructureSet>> holdersByKey,
            Map<Holder<StructureSet>, Holder<StructureSet>> scaledByIdentity
    ) {
        if (scaledByIdentity.containsKey(holder)) {
            return scaledByIdentity.get(holder);
        }
        if (!dependsOnFrequencyOverride(holder, importedStructures, holdersByKey)) {
            scaledByIdentity.put(holder, holder);
            return holder;
        }

        ResourceKey<StructureSet> holderKey = structureSetResourceKey(holder).orElseThrow(() ->
                new IllegalStateException(
                        "An affected native structure-set exclusion graph has an unkeyed holder"));
        ReboundStructureSetHolder scaledHolder = new ReboundStructureSetHolder(holderKey);
        scaledByIdentity.put(holder, scaledHolder);

        StructureSet originalSet = holder.value();
        StructurePlacement originalPlacement = originalSet.placement();
        Optional<StructurePlacement.ExclusionZone> originalZone = exclusionZone(originalPlacement);
        Optional<StructurePlacement.ExclusionZone> scaledZone = originalZone;
        if (originalZone.isPresent()) {
            Holder<StructureSet> target = canonicalHolder(
                    originalZone.get().otherSet(), holdersByKey);
            Holder<StructureSet> scaledTarget = scaleFrequencyHolder(
                    target, importedStructures, holdersByKey, scaledByIdentity);
            if (scaledTarget != originalZone.get().otherSet()) {
                scaledZone = Optional.of(new StructurePlacement.ExclusionZone(
                        scaledTarget, originalZone.get().chunkCount()));
            }
        }

        double multiplier = importedStructures.frequencyMultiplier(structureSetKey(holder));
        StructurePlacement scaledPlacement = multiplier == 1D && scaledZone.equals(originalZone)
                ? originalPlacement
                : copyPlacement(originalPlacement, scaledZone, multiplier);
        scaledHolder.bind(new StructureSet(originalSet.structures(), scaledPlacement));
        return scaledHolder;
    }

    private static boolean dependsOnFrequencyOverride(
            Holder<StructureSet> holder,
            IrisImportedStructureControl importedStructures,
            Map<String, Holder<StructureSet>> holdersByKey
    ) {
        Set<Holder<StructureSet>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Holder<StructureSet> current = holder;
        while (visited.add(current)) {
            if (importedStructures.frequencyMultiplier(structureSetKey(current)) != 1D) {
                return true;
            }
            Optional<StructurePlacement.ExclusionZone> zone =
                    exclusionZone(current.value().placement());
            if (zone.isEmpty()) {
                return false;
            }
            current = canonicalHolder(zone.get().otherSet(), holdersByKey);
        }
        return false;
    }

    private static Holder<StructureSet> canonicalHolder(
            Holder<StructureSet> holder,
            Map<String, Holder<StructureSet>> holdersByKey
    ) {
        String key = structureSetKey(holder);
        return key == null ? holder : holdersByKey.getOrDefault(key, holder);
    }

    private static Holder<StructureSet> replacementHolder(
            Holder<StructureSet> original,
            StructureSet replacement
    ) {
        Optional<ResourceKey<StructureSet>> key = structureSetResourceKey(original);
        if (key.isEmpty()) {
            return Holder.direct(replacement);
        }
        ReboundStructureSetHolder holder = new ReboundStructureSetHolder(key.get());
        holder.bind(replacement);
        return holder;
    }

    private static Optional<ResourceKey<StructureSet>> structureSetResourceKey(
            Holder<StructureSet> holder
    ) {
        Optional<ResourceKey<StructureSet>> key = holder.unwrapKey();
        if (key.isPresent()) {
            return key;
        }
        if (holder.value().placement()
                instanceof ChunkGeneratorStructureState.KeyedRandomSpreadStructurePlacement keyedPlacement) {
            return Optional.of(keyedPlacement.key);
        }
        return Optional.empty();
    }

    private static StructurePlacement copyPlacement(
            StructurePlacement placement,
            Optional<StructurePlacement.ExclusionZone> exclusionZone,
            double frequencyMultiplier
    ) {
        Vec3i locateOffset = (Vec3i) declaredFieldValue(
                StructurePlacement.class, placement, Vec3i.class);
        StructurePlacement.FrequencyReductionMethod frequencyReductionMethod =
                (StructurePlacement.FrequencyReductionMethod) declaredFieldValue(
                        StructurePlacement.class,
                        placement,
                        StructurePlacement.FrequencyReductionMethod.class);
        float frequency = (float) declaredFieldValue(
                StructurePlacement.class, placement, float.class);
        int salt = (int) declaredFieldValue(
                StructurePlacement.class, placement, int.class);

        if (placement.getClass()
                == ChunkGeneratorStructureState.KeyedRandomSpreadStructurePlacement.class) {
            ChunkGeneratorStructureState.KeyedRandomSpreadStructurePlacement keyedPlacement =
                    (ChunkGeneratorStructureState.KeyedRandomSpreadStructurePlacement) placement;
            NativeStructureFrequencyScale scale = NativeStructureFrequencyScale.randomSpread(
                    frequency, keyedPlacement.spacing(), keyedPlacement.separation(), frequencyMultiplier);
            return new ChunkGeneratorStructureState.KeyedRandomSpreadStructurePlacement(
                    keyedPlacement.key,
                    locateOffset,
                    frequencyReductionMethod,
                    scale.frequency(),
                    salt,
                    exclusionZone,
                    scale.spacing(),
                    keyedPlacement.separation(),
                    keyedPlacement.spreadType());
        }
        if (placement.getClass() == RandomSpreadStructurePlacement.class) {
            RandomSpreadStructurePlacement randomSpread =
                    (RandomSpreadStructurePlacement) placement;
            NativeStructureFrequencyScale scale = NativeStructureFrequencyScale.randomSpread(
                    frequency, randomSpread.spacing(), randomSpread.separation(), frequencyMultiplier);
            return new RandomSpreadStructurePlacement(
                    locateOffset,
                    frequencyReductionMethod,
                    scale.frequency(),
                    salt,
                    exclusionZone,
                    scale.spacing(),
                    randomSpread.separation(),
                    randomSpread.spreadType());
        }
        if (placement.getClass() == ConcentricRingsStructurePlacement.class) {
            ConcentricRingsStructurePlacement rings =
                    (ConcentricRingsStructurePlacement) placement;
            float scaledFrequency = NativeStructureFrequencyScale.probability(
                    frequency, frequencyMultiplier);
            return new ConcentricRingsStructurePlacement(
                    locateOffset,
                    frequencyReductionMethod,
                    scaledFrequency,
                    salt,
                    exclusionZone,
                    rings.distance(),
                    rings.spread(),
                    rings.count(),
                    rings.preferredBiomes());
        }
        throw new IllegalStateException("Unsupported native structure placement in an affected "
                + "frequency-override or datapack-scope graph: "
                + placement.getClass().getName());
    }

    private static Object declaredFieldValue(
            Class<?> declaringType,
            Object target,
            Class<?> fieldType
    ) {
        Field match = null;
        for (Field field : declaringType.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != fieldType) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException("Ambiguous " + fieldType.getName() + " field on "
                        + declaringType.getName());
            }
            match = field;
        }
        if (match == null) {
            throw new IllegalStateException("Missing " + fieldType.getName() + " field on "
                    + declaringType.getName());
        }
        try {
            match.setAccessible(true);
            return match.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read " + fieldType.getName() + " field on "
                    + declaringType.getName(), e);
        }
    }

    record Selection(
            List<Holder<StructureSet>> structureSets,
            int retainedManagedSets,
            int excludedManagedSets
    ) {
    }

    private static final class ReboundStructureSetHolder extends Holder.Reference<StructureSet> {
        private ReboundStructureSetHolder(ResourceKey<StructureSet> key) {
            super(Type.STAND_ALONE, new HolderOwner<>() {
            }, key, null);
        }

        private void bind(StructureSet structureSet) {
            bindValue(structureSet);
        }
    }
}
