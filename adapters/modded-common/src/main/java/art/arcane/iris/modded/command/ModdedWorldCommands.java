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
import art.arcane.iris.core.pack.BrokenPackException;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.MainWorldService;
import art.arcane.iris.modded.ModdedDimensionManager;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedModConfig;
import art.arcane.iris.modded.ModdedPackInstaller;
import art.arcane.iris.modded.ModdedPrimaryWorldRouter;
import art.arcane.iris.modded.ModdedStartup;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class ModdedWorldCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final String DEFAULT_NAMESPACE = "irisworldgen";
    private static final long DEFAULT_SEED = 1337L;
    private static final SuggestionProvider<CommandSourceStack> LOADED_DIMENSIONS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> SharedSuggestionProvider.suggest(loadedIrisDimensions(context.getSource().getServer()), builder);

    private ModdedWorldCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(GATE);

        root.executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name));

        root.then(Commands.literal("status")
                .executes((CommandContext<CommandSourceStack> context) -> status(context.getSource())));
        root.then(Commands.literal("list")
                .executes((CommandContext<CommandSourceStack> context) -> list(context.getSource())));
        root.then(Commands.literal("ls")
                .executes((CommandContext<CommandSourceStack> context) -> list(context.getSource())));

        root.then(enableTree("enable"));
        root.then(enableTree("create"));
        root.then(replaceOverworldTree());
        root.then(mainWorldTree());

        root.then(disableTree());
        root.then(deleteTree("delete"));
        root.then(deleteTree("remove"));
        root.then(deleteTree("rm"));

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> enableTree(String name) {
        return Commands.literal(name)
                .then(Commands.argument("dimension", IdentifierArgument.id())
                        .then(Commands.argument("pack", StringArgumentType.string()).suggests(IrisModdedCommands.PACK_NAMES)
                                .executes((CommandContext<CommandSourceStack> context) -> enable(context.getSource(),
                                        dimensionArgument(context),
                                        StringArgumentType.getString(context, "pack"),
                                        null))
                                .then(Commands.argument("seed", StringArgumentType.word())
                                        .executes((CommandContext<CommandSourceStack> context) -> enable(context.getSource(),
                                                dimensionArgument(context),
                                                StringArgumentType.getString(context, "pack"),
                                                StringArgumentType.getString(context, "seed"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> disableTree() {
        return Commands.literal("disable")
                .then(Commands.argument("dimension", IdentifierArgument.id()).suggests(LOADED_DIMENSIONS)
                        .executes((CommandContext<CommandSourceStack> context) -> disable(context.getSource(), dimensionArgument(context), false)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> deleteTree(String name) {
        return Commands.literal(name)
                .then(Commands.argument("dimension", IdentifierArgument.id()).suggests(LOADED_DIMENSIONS)
                        .executes((CommandContext<CommandSourceStack> context) -> disable(context.getSource(), dimensionArgument(context), true)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> replaceOverworldTree() {
        return Commands.literal("replace-overworld")
                .then(Commands.argument("pack", StringArgumentType.string()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> replaceOverworld(context.getSource(),
                                StringArgumentType.getString(context, "pack"),
                                null))
                        .then(Commands.argument("seed", StringArgumentType.word())
                                .executes((CommandContext<CommandSourceStack> context) -> replaceOverworld(context.getSource(),
                                        StringArgumentType.getString(context, "pack"),
                                        StringArgumentType.getString(context, "seed")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mainWorldTree() {
        return Commands.literal("mainworld")
                .then(Commands.literal("off")
                        .executes((CommandContext<CommandSourceStack> context) -> clearMainWorld(context.getSource())))
                .then(Commands.argument("pack", StringArgumentType.string()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> mainWorld(context.getSource(),
                                StringArgumentType.getString(context, "pack"),
                                null))
                        .then(Commands.argument("seed", StringArgumentType.word())
                                .executes((CommandContext<CommandSourceStack> context) -> mainWorld(context.getSource(),
                                        StringArgumentType.getString(context, "pack"),
                                        StringArgumentType.getString(context, "seed")))));
    }

    public static int createWorld(CommandSourceStack source, String name, String pack, long seed) {
        String[] packRef = parsePackRef(pack);
        return enable(source, name, packRef[0], packRef[1], seed);
    }

    private static int enable(CommandSourceStack source, String targetDimension, String packRaw, String seedRaw) {
        Long seed = parseSeed(source, seedRaw);
        if (seed == null) {
            return 0;
        }
        String[] packRef = parsePackRef(packRaw);
        return enable(source, targetDimension, packRef[0], packRef[1], seed);
    }

    private static int enable(CommandSourceStack source, String targetDimension, String pack, String packDimension, long seed) {
        MinecraftServer server = source.getServer();
        String dimensionId;
        try {
            dimensionId = normalizeDimensionId(targetDimension);
        } catch (IllegalArgumentException e) {
            IrisModdedCommands.fail(source, e.getMessage());
            return 0;
        }
        if (!validPackRef(source, pack, packDimension)) {
            return 0;
        }
        File packFolder = new File(ModdedPackCommands.packsRoot(), pack);
        if (packFolder.isDirectory()) {
            return enableInstalled(source, server, dimensionId, pack, packDimension, seed);
        }
        IrisModdedCommands.ok(source, "Pack '" + pack + "' is not installed; downloading IrisDimensions/" + pack + "...");
        Thread thread = new Thread(() -> {
            boolean installed = ModdedPackInstaller.install(ModdedEngineBootstrap.loader().configDir(), pack, "master",
                    (String line) -> server.execute(() -> IrisModdedCommands.ok(source, line)));
            server.execute(() -> {
                if (!installed || !packFolder.isDirectory()) {
                    IrisModdedCommands.fail(source, "Pack '" + pack + "' could not be downloaded; check the name or install it with /iris download " + pack + ".");
                    return;
                }
                enableInstalled(source, server, dimensionId, pack, packDimension, seed);
            });
        }, "Iris World Pack Download");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int enableInstalled(CommandSourceStack source, MinecraftServer server, String dimensionId, String pack, String packDimension, long seed) {
        if (blockIfPackBroken(source, dimensionId, pack)) {
            return 0;
        }
        if (!loadPackDimension(source, pack, packDimension)) {
            return 0;
        }
        try {
            ModdedDimensionManager.createPersistent(server, dimensionId, pack, packDimension, seed);
        } catch (Throwable e) {
            LOGGER.error("Iris world injection failed for {} (pack={} dim={})", dimensionId, pack, packDimension, e);
            IrisModdedCommands.fail(source, "Failed to inject Iris world '" + dimensionId + "': " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return 0;
        }
        IrisModdedCommands.ok(source, "Created Iris world " + dimensionId + " from pack '" + pack + "' dimension '" + packDimension + "' (seed " + seed + ").");
        IrisModdedCommands.ok(source, "It is live now and re-injected on every startup. Teleport in with /iris world status or a portal; no restart required.");
        return 1;
    }

    private static int replaceOverworld(CommandSourceStack source, String packRaw, String seedRaw) {
        MinecraftServer server = source.getServer();
        Long seed = parseSeed(source, seedRaw);
        if (seed == null) {
            return 0;
        }
        String[] packRef = parsePackRef(packRaw);
        String pack = packRef[0];
        String packDimension = packRef[1];
        if (!validPackRef(source, pack, packDimension)) {
            return 0;
        }
        String dimensionId = DEFAULT_NAMESPACE + ":primary";
        if (blockIfPackBroken(source, dimensionId, pack)) {
            return 0;
        }
        if (!loadPackDimension(source, pack, packDimension)) {
            return 0;
        }
        try {
            ModdedDimensionManager.createPersistent(server, dimensionId, pack, packDimension, seed);
        } catch (Throwable e) {
            LOGGER.error("Iris primary world injection failed for {} (pack={} dim={})", dimensionId, pack, packDimension, e);
            IrisModdedCommands.fail(source, "Failed to inject Iris primary world: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return 0;
        }
        ModdedModConfig.setPrimaryWorld(dimensionId);
        ModdedPrimaryWorldRouter.clear();
        IrisModdedCommands.ok(source, "Iris primary world set to " + dimensionId + " (pack '" + pack + "' dimension '" + packDimension + "' seed " + seed + ").");
        IrisModdedCommands.ok(source, "The vanilla overworld generator cannot be hot-swapped, so this does NOT regenerate the existing overworld.");
        IrisModdedCommands.ok(source, "Instead, " + dimensionId + " is now the configured primary world: players in the vanilla overworld are routed there on join, and it re-injects on every startup.");
        return 1;
    }

    private static int clearMainWorld(CommandSourceStack source) {
        ModdedModConfig.setMainWorld("", 0L);
        MainWorldService.clearOverride();
        IrisModdedCommands.ok(source, "Iris main world override cleared. The overworld keeps its current generator; edit server.properties level-type and restart to change it back.");
        return 1;
    }

    private static int mainWorld(CommandSourceStack source, String packRaw, String seedRaw) {
        MinecraftServer server = source.getServer();
        long seed;
        if (seedRaw == null || seedRaw.isBlank()) {
            seed = 0L;
        } else if (seedRaw.equalsIgnoreCase("random")) {
            long rolled = ThreadLocalRandom.current().nextLong();
            seed = rolled == 0L ? 1L : rolled;
        } else {
            try {
                seed = Long.parseLong(seedRaw.trim());
            } catch (NumberFormatException e) {
                IrisModdedCommands.fail(source, "Invalid seed '" + seedRaw + "'. Use a number or 'random'.");
                return 0;
            }
        }
        String[] packRef = parsePackRef(packRaw);
        String pack = packRef[0];
        String packDimension = packRef[1];
        if (!validPackRef(source, pack, packDimension)) {
            return 0;
        }
        File packFolder = new File(ModdedPackCommands.packsRoot(), pack);
        if (packFolder.isDirectory()) {
            return applyMainWorld(source, pack, packDimension, packRaw, seed);
        }
        IrisModdedCommands.ok(source, "Pack '" + pack + "' is not installed; downloading IrisDimensions/" + pack + "...");
        Thread thread = new Thread(() -> {
            boolean installed = ModdedPackInstaller.install(ModdedEngineBootstrap.loader().configDir(), pack, "master",
                    (String line) -> server.execute(() -> IrisModdedCommands.ok(source, line)));
            server.execute(() -> {
                if (!installed || !packFolder.isDirectory()) {
                    IrisModdedCommands.fail(source, "Pack '" + pack + "' could not be downloaded; check the name or install it with /iris download " + pack + ".");
                    return;
                }
                applyMainWorld(source, pack, packDimension, packRaw, seed);
            });
        }, "Iris Main World Pack Download");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int applyMainWorld(CommandSourceStack source, String pack, String packDimension, String packRef, long seed) {
        if (blockIfPackBroken(source, "the main world", pack)) {
            return 0;
        }
        try {
            if (!loadPackDimension(source, pack, packDimension)) {
                return 0;
            }
        } catch (Throwable e) {
            LOGGER.error("Iris main world pack load failed for {} (dim={})", pack, packDimension, e);
            IrisModdedCommands.fail(source, "Pack '" + pack + "' is not ready yet (still loading or validating). Try the command again in a moment.");
            return 0;
        }
        ModdedModConfig.setMainWorld(packRef, seed);
        if (!MainWorldService.stage(packRef, seed)) {
            IrisModdedCommands.fail(source, "Failed to write server.properties; check file permissions and set level-type manually.");
            return 0;
        }
        String preset = MainWorldService.presetIdFor(packRef);
        IrisModdedCommands.ok(source, "Iris main world set to '" + pack + "' (preset " + preset + ", seed " + (seed == 0L ? "random" : Long.toString(seed)) + ").");
        IrisModdedCommands.ok(source, "server.properties level-type is now " + preset + ". On the next restart the overworld, nether, and end regenerate as this Iris world.");
        IrisModdedCommands.ok(source, "Player data (inventories, advancements, stats) is kept; existing terrain in those dimensions is replaced. This applies once.");
        if (ModdedModConfig.get().mainWorldAutoRestart()) {
            IrisModdedCommands.ok(source, "mainWorldAutoRestart is enabled - stopping the server now so your restart wrapper brings it back on the new main world.");
            source.getServer().halt(false);
        } else {
            IrisModdedCommands.ok(source, "Restart the server now to generate it. (Set mainWorldAutoRestart=true in modded.json to have Iris stop the server for you.)");
        }
        return 1;
    }

    private static boolean blockIfPackBroken(CommandSourceStack source, String dimensionId, String pack) {
        try {
            ModdedStartup.requirePackForWorldCreation(pack);
            return false;
        } catch (BrokenPackException e) {
            IrisModdedCommands.fail(source, "Refusing to create world '" + dimensionId
                    + "' using pack '" + pack + "' because required validation failed:");
            for (String reason : e.getReasons()) {
                IrisModdedCommands.fail(source, "  - " + reason);
            }
            IrisModdedCommands.fail(source, "Fix the pack and run /iris pack validate " + pack + " to revalidate.");
            return true;
        }
    }

    private static boolean loadPackDimension(CommandSourceStack source, String pack, String packDimension) {
        File packFolder = new File(ModdedPackCommands.packsRoot(), pack);
        if (!packFolder.isDirectory()) {
            IrisModdedCommands.fail(source, "Pack '" + pack + "' was not found under " + ModdedPackCommands.packsRoot().getAbsolutePath());
            return false;
        }
        IrisData data = IrisData.get(packFolder);
        IrisDimension dimension = data.getDimensionLoader().load(packDimension);
        if (dimension == null) {
            IrisModdedCommands.fail(source, "Pack '" + pack + "' does not contain dimensions/" + packDimension + ".json");
            return false;
        }
        return true;
    }

    private static String[] parsePackRef(String raw) {
        String value = raw.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            return new String[]{value.substring(0, colon), value.substring(colon + 1)};
        }
        return new String[]{value, value};
    }

    private static boolean validPackRef(CommandSourceStack source, String pack, String packDimension) {
        if (pack.matches("[A-Za-z0-9_.-]+") && !pack.contains("..")
                && packDimension.matches("[A-Za-z0-9_/.-]+") && !packDimension.contains("..")) {
            return true;
        }
        IrisModdedCommands.fail(source, "Invalid pack reference '" + pack + (pack.equals(packDimension) ? "" : ":" + packDimension) + "'. Use pack or pack:dimensionKey.");
        return false;
    }

    private static Long parseSeed(CommandSourceStack source, String seedRaw) {
        if (seedRaw == null || seedRaw.isBlank()) {
            return DEFAULT_SEED;
        }
        String value = seedRaw.trim();
        if (value.equalsIgnoreCase("random")) {
            return ThreadLocalRandom.current().nextLong();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            IrisModdedCommands.fail(source, "Invalid seed '" + seedRaw + "'. Use a number or 'random'.");
            return null;
        }
    }

    private static int disable(CommandSourceStack source, String targetDimension, boolean wipeStorage) {
        MinecraftServer server = source.getServer();
        String dimensionId;
        try {
            dimensionId = normalizeDimensionId(targetDimension);
        } catch (IllegalArgumentException e) {
            IrisModdedCommands.fail(source, e.getMessage());
            return 0;
        }
        boolean removed;
        try {
            removed = ModdedDimensionManager.removePersistent(server, dimensionId, wipeStorage);
        } catch (Throwable e) {
            LOGGER.error("Iris world removal failed for {}", dimensionId, e);
            IrisModdedCommands.fail(source, "Failed to remove Iris world '" + dimensionId + "': " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return 0;
        }
        if (dimensionId.equals(ModdedModConfig.get().primaryWorld())) {
            ModdedModConfig.setPrimaryWorld("");
            ModdedPrimaryWorldRouter.clear();
        }
        if (!removed) {
            if (wipeStorage) {
                IrisModdedCommands.ok(source, "Iris world '" + dimensionId + "' was not loaded; cleared its persistent registry entry and wiped its stored chunk/mantle data.");
            } else {
                IrisModdedCommands.ok(source, "Iris world '" + dimensionId + "' was not loaded; cleared its persistent registry entry. World data on disk is kept.");
            }
            return 1;
        }
        if (wipeStorage) {
            IrisModdedCommands.ok(source, "Deleted Iris world '" + dimensionId + "': evacuated, unloaded, chunk/mantle data wiped, and dropped from the startup registry.");
        } else {
            IrisModdedCommands.ok(source, "Disabled Iris world '" + dimensionId + "': evacuated, unloaded, and dropped from the startup registry.");
            IrisModdedCommands.ok(source, "World data on disk is kept; re-enable with /iris world enable " + dimensionId + " <pack> or delete it with /iris world delete " + dimensionId + ".");
        }
        return 1;
    }

    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        int loaded = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator generator) {
                loaded++;
                IrisModdedCommands.ok(source, "Loaded Iris level: " + level.dimension().identifier() + " -> pack '" + generator.activePack() + "' dimension '" + generator.activeDimensionKey() + "'");
            }
        }
        String primary = ModdedModConfig.get().primaryWorld();
        if (!primary.isBlank()) {
            IrisModdedCommands.ok(source, "Primary world: " + primary + (ModdedModConfig.get().routePlayersToPrimaryWorld() ? " (players routed there)" : " (routing disabled)"));
        }
        if (loaded == 0) {
            IrisModdedCommands.fail(source, "No Iris dimensions are currently loaded. Create one with /iris world create <name> <pack>.");
        }
        return loaded > 0 ? 1 : 0;
    }

    private static int list(CommandSourceStack source) {
        List<String> dimensions = loadedIrisDimensions(source.getServer());
        IrisModdedCommands.ok(source, "Loaded Iris dimensions: " + dimensions.size());
        for (String dimension : dimensions) {
            IrisModdedCommands.ok(source, "  - " + dimension);
        }
        if (dimensions.isEmpty()) {
            IrisModdedCommands.ok(source, "Use /iris world create <name> <pack> to inject one without restarting.");
        }
        return 1;
    }

    private static List<String> loadedIrisDimensions(MinecraftServer server) {
        List<String> dimensions = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                dimensions.add(level.dimension().identifier().toString());
            }
        }
        return dimensions;
    }

    private static String dimensionArgument(CommandContext<CommandSourceStack> context) {
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if ("dimension".equals(node.getNode().getName())) {
                return node.getRange().get(context.getInput());
            }
        }
        return IdentifierArgument.getId(context, "dimension").toString();
    }

    private static String normalizeDimensionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing dimension id.");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        String namespace = DEFAULT_NAMESPACE;
        String path = normalized;
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            namespace = normalized.substring(0, colon);
            path = normalized.substring(colon + 1);
        }
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+") || path.startsWith("/") || path.endsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("Invalid dimension id '" + value + "'. Use name or namespace:path.");
        }
        return namespace + ":" + path;
    }
}
