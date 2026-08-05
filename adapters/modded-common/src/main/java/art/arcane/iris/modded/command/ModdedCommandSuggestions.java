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

import art.arcane.iris.core.pack.PackDirectoryResolver;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedServerLevels;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class ModdedCommandSuggestions {
    static final SuggestionProvider<CommandSourceStack> BIOME_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestBiomeKeys(context, builder);
    static final SuggestionProvider<CommandSourceStack> REGION_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestRegionKeys(context, builder);
    static final SuggestionProvider<CommandSourceStack> OBJECT_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestObjectKeys(context, builder);
    static final SuggestionProvider<CommandSourceStack> STRUCTURE_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestStructureKeys(context, builder);
    static final SuggestionProvider<CommandSourceStack> POI_TYPES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> SharedSuggestionProvider.suggest(List.of("buried_treasure"), builder);
    static final SuggestionProvider<CommandSourceStack> PACK_NAMES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestPackNames(context, builder);
    static final SuggestionProvider<CommandSourceStack> DIMENSION_NAMES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestDimensionNames(context, builder);

    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final int TAB_FAILURE_KEYS_MAX = 256;
    private static final Set<String> REPORTED_TAB_FAILURES = ConcurrentHashMap.newKeySet();

    private ModdedCommandSuggestions() {
    }

    private static CompletableFuture<Suggestions> suggestBiomeKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = IrisModdedCommands.engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getBiomeLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable e) {
            warnTabFailure("biome keys", context.getSource(), e);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRegionKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = IrisModdedCommands.engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getDimension().getRegions(), builder);
            }
        } catch (Throwable e) {
            warnTabFailure("region keys", context.getSource(), e);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestObjectKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = IrisModdedCommands.engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getObjectLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable e) {
            warnTabFailure("object keys", context.getSource(), e);
        }
        return builder.buildFuture();
    }

    static CompletableFuture<Suggestions> suggestStructureKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = IrisModdedCommands.engineFor(context.getSource().getLevel());
            Collection<String> irisKeys = engine == null ? List.of() : IrisStructureLocator.placedKeys(engine);
            Registry<Structure> registry = context.getSource().getServer().registryAccess().lookupOrThrow(Registries.STRUCTURE);
            List<String> nativeKeys = new ArrayList<>(registry.keySet().size());
            for (Identifier identifier : registry.keySet()) {
                nativeKeys.add(identifier.toString());
            }
            return SharedSuggestionProvider.suggest(combineStructureKeys(irisKeys, nativeKeys), builder);
        } catch (Throwable e) {
            warnTabFailure("structure keys", context.getSource(), e);
        }
        return builder.buildFuture();
    }

    static void warnTabFailure(String suggestion, CommandSourceStack source, Throwable error) {
        String origin = tabOrigin(source);
        if (!REPORTED_TAB_FAILURES.add(suggestion + '|' + origin + '|' + error.getClass().getName())) {
            return;
        }
        if (REPORTED_TAB_FAILURES.size() > TAB_FAILURE_KEYS_MAX) {
            REPORTED_TAB_FAILURES.clear();
        }
        LOGGER.warn("Iris tab-complete for {} in {} failed; suggestions will be empty", suggestion, origin, error);
    }

    private static String tabOrigin(CommandSourceStack source) {
        if (source == null) {
            return "<no source>";
        }
        try {
            return source.getLevel().dimension().identifier().toString();
        } catch (Throwable originFailure) {
            return "<no level>";
        }
    }

    static List<String> combineStructureKeys(Collection<String> irisKeys, Collection<String> nativeKeys) {
        Set<String> combined = new TreeSet<>();
        combined.addAll(irisKeys);
        combined.addAll(nativeKeys);
        return List.copyOf(combined);
    }

    private static CompletableFuture<Suggestions> suggestPackNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        Set<String> names = new TreeSet<>();
        names.add("overworld");
        try {
            File packs = ModdedEngineBootstrap.loader().configDir().resolve("irisworldgen").resolve("packs").toFile();
            for (File child : PackDirectoryResolver.listVisiblePackDirectories(packs)) {
                String packName = child.getName();
                names.add(packName);
                File dimensions = new File(child, "dimensions");
                File[] dimensionFiles = dimensions.listFiles(
                        (File directory, String name) -> name.endsWith(".json"));
                if (dimensionFiles == null) {
                    continue;
                }
                for (File dimensionFile : dimensionFiles) {
                    String fileName = dimensionFile.getName();
                    names.add(packName + ":" + fileName.substring(0, fileName.length() - 5));
                }
            }
        } catch (Throwable e) {
            warnTabFailure("pack names", context.getSource(), e);
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static CompletableFuture<Suggestions> suggestDimensionNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        List<String> names = new ArrayList<>();
        for (ServerLevel level : ModdedServerLevels.levels(context.getSource().getServer())) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                names.add(level.dimension().identifier().toString());
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }
}
