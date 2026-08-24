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


import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Boot audit for the Iris mixin configs. Mixin registration is per-loader (fabric.mod.json entry, NeoForge
 * mods.toml [[mixins]] block, Forge shadowJar MixinConfigs manifest attribute) and a config that never gets
 * registered fails silently: no crash, the hooks simply never exist, and entity persistence, custom mob loot
 * and the Iris world-type labels quietly stop working.
 *
 * <p>Application is checked structurally: Mixin transfers an {@code @Inject} handler into the target class,
 * so the handler's presence on the target proves the mixin applied. Mixin 0.8.7 renames the transferred
 * method to {@code handler$<ids>$<originalName>}, so the declared name is matched as an exact name or as a
 * {@code $}-prefixed suffix. Client targets are resolved by name so this class stays free of client
 * references and is safe on a dedicated server.
 */
public final class ModdedMixinAudit {
    private static final AtomicBoolean AUDITED = new AtomicBoolean(false);

    private static final List<ExpectedMixin> EXPECTED = List.of(
            new ExpectedMixin("EntityPersistenceMixin", "entity",
                    "net.minecraft.world.entity.Entity", "iris$applyGeneratedPersistence",
                    false, ModdedMixinFlags::entityPersistenceRan),
            new ExpectedMixin("LivingEntityLootMixin", "entity",
                    "net.minecraft.world.entity.LivingEntity", "iris$replaceBaseLoot",
                    false, ModdedMixinFlags::livingEntityLootRan),
            new ExpectedMixin("MobAwarenessMixin", "entity",
                    "net.minecraft.world.entity.Mob", "iris$tickUnawareMob",
                    false, ModdedMixinFlags::mobAwarenessRan),
            new ExpectedMixin("StructureTemplatePaletteConcurrencyMixin", "common",
                    "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate$Palette",
                    "iris$installConcurrentBlockCache",
                    false, ModdedMixinFlags::structureTemplatePaletteRan),
            new ExpectedMixin("IrisWorldOpenFlowsMixin", "client",
                    "net.minecraft.client.gui.screens.worldselection.WorldOpenFlows",
                    "iris$openWorldCheckWorldStemCompatibility",
                    true, ModdedMixinFlags::worldOpenFlowsRan),
            new ExpectedMixin("IrisWorldTypeEntryMixin", "client",
                    "net.minecraft.client.gui.screens.worldselection.WorldCreationUiState$WorldTypeEntry",
                    "iris$describePreset",
                    true, ModdedMixinFlags::worldTypeEntryRan));

    private ModdedMixinAudit() {
    }

    public static void runOnce() {
        if (!AUDITED.compareAndSet(false, true)) {
            return;
        }
        audit(ModdedEngineBootstrap.loader().platformName(),
                ModdedEngineBootstrap.loader().clientEnvironment());
    }

    static void reset() {
        AUDITED.set(false);
    }

    static void audit(String platform, boolean clientEnvironment) {
        List<String> missing = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        for (ExpectedMixin expected : EXPECTED) {
            if (expected.clientOnly() && !clientEnvironment) {
                continue;
            }
            if (isApplied(expected)) {
                applied.add(expected.mixinName() + (expected.ran().getAsBoolean() ? "" : " (not yet exercised)"));
            } else {
                missing.add(expected.config() + '/' + expected.mixinName() + " -> " + expected.targetClass());
            }
        }
        if (missing.isEmpty()) {
            ModdedIrisLog.info("Iris mixin audit ok on {} ({} dist): {}", platform,
                    clientEnvironment ? "client" : "server", String.join(", ", applied));
            return;
        }
        ModdedIrisLog.error("===============================================================");
        ModdedIrisLog.error("Iris mixin audit FAILED on {} ({} dist): {} of {} expected mixin(s) were not applied.",
                platform, clientEnvironment ? "client" : "server", missing.size(),
                missing.size() + applied.size());
        for (String entry : missing) {
            ModdedIrisLog.error("  missing: {}", entry);
        }
        ModdedIrisLog.error("The mixin config was not registered for this loader (fabric.mod.json mixins, neoforge.mods.toml [[mixins]], forge MixinConfigs manifest attribute).");
        ModdedIrisLog.error("Entity persistence, custom mob loot, parallel structure safety, or Iris world-type labels are disabled until this is fixed.");
        ModdedIrisLog.error("===============================================================");
    }

    private static boolean isApplied(ExpectedMixin expected) {
        try {
            Class<?> target = Class.forName(expected.targetClass(), false,
                    ModdedMixinAudit.class.getClassLoader());
            for (Method method : target.getDeclaredMethods()) {
                // Mixin 0.8.7 renames applied @Inject handlers to handler$<ids>$<originalName>, so an exact
                // name match alone reports every applied mixin as missing.
                String name = method.getName();
                if (name.equals(expected.handlerMethod())
                        || name.endsWith('$' + expected.handlerMethod())) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            ModdedIrisLog.warn("Iris mixin audit could not inspect {}", expected.targetClass(), unavailable);
            return true;
        }
    }

    private record ExpectedMixin(String mixinName, String config, String targetClass, String handlerMethod,
                                 boolean clientOnly, BooleanSupplier ran) {
    }
}
