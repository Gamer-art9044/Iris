package art.arcane.iris.nativegen;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.NativeStructurePlacementPlanner;
import art.arcane.iris.engine.framework.NativeStructureStartPlan;
import art.arcane.iris.engine.object.NativeStructureSuppression;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class NativeStructureStartInjector {
    private NativeStructureStartInjector() {
    }

    public static Map<Structure, NativeStructureStartPlan> inject(InjectionContext context) {
        Objects.requireNonNull(context, "Native structure injection context must not be null");
        ChunkAccess chunk = context.chunk();
        Registry<Structure> registry = context.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        SectionPos section = SectionPos.bottomOf(chunk);
        Map<Structure, NativeStructureStartPlan> configuredStarts = new LinkedHashMap<>();
        for (NativeStructureStartPlan plan : NativeStructurePlacementPlanner.plansAt(
                context.engine(), chunk.getPos().x(), chunk.getPos().z())) {
            Identifier identifier = Identifier.tryParse(plan.source().getStructure());
            if (identifier == null) {
                throw new IllegalStateException("Configured native structure key is invalid: "
                        + plan.source().getStructure());
            }
            Structure structure = registry.getValue(identifier);
            if (structure == null) {
                throw new IllegalStateException("Configured native structure is not registered: " + identifier);
            }
            Holder<Structure> holder = registry.wrapAsHolder(structure);
            if (configuredStarts.containsKey(structure)) {
                throw new IllegalStateException("Multiple configured native structure placements selected '"
                        + identifier + "' in chunk " + chunk.getPos().x() + "," + chunk.getPos().z()
                        + "; Minecraft can persist only one start per registered structure per chunk");
            }
            StructureStart existing = context.structureManager().getStartForStructure(
                    section, structure, chunk);
            boolean replacement = plan.placement().getNativeSuppression()
                    == NativeStructureSuppression.REPLACE_SOURCE;
            if (!replacement && existing != null && existing.isValid()) {
                continue;
            }
            int references = existing != null && existing.isValid() ? existing.getReferences() : 0;
            NativeStructureFactory.GenerationContext generationContext =
                    new NativeStructureFactory.GenerationContext(
                            context.registryAccess(),
                            context.generator(),
                            context.biomeSource(),
                            context.structureState().randomState(),
                            context.templateManager(),
                            context.structureState().getLevelSeed(),
                            context.levelKey(),
                            chunk,
                            biome -> true,
                            context.generator().getSeaLevel(),
                            (x, z) -> context.engine().getHeight(x, z, true)
                                    + context.engine().getMinHeight()
                    );
            StructureStart generated = NativeStructureFactory.generate(
                    generationContext, holder, plan, references);
            if (!generated.isValid()) {
                throw new IllegalStateException("Configured native structure '" + identifier
                        + "' produced no valid start in chunk " + chunk.getPos().x()
                        + "," + chunk.getPos().z());
            }
            context.structureManager().setStartForStructure(
                    section, structure, generated, chunk);
            configuredStarts.put(structure, plan);
        }
        return Map.copyOf(configuredStarts);
    }

    public record InjectionContext(
            Engine engine,
            RegistryAccess registryAccess,
            ChunkGeneratorStructureState structureState,
            StructureManager structureManager,
            ChunkAccess chunk,
            StructureTemplateManager templateManager,
            ResourceKey<Level> levelKey,
            ChunkGenerator generator,
            BiomeSource biomeSource
    ) {
    }
}
