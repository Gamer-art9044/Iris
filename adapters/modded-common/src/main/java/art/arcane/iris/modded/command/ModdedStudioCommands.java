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

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.gui.GuiHost;
import art.arcane.iris.core.gui.NoiseExplorerGUI;
import art.arcane.iris.core.gui.VisionGUI;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.pack.StructurePackageClosure;
import art.arcane.iris.core.project.IrisProjectCopier;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeGeneratorLink;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisEntitySpawn;
import art.arcane.iris.engine.object.IrisGenerator;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisSpawner;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.modded.ModdedDimensionManager;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedPackInstaller;
import art.arcane.iris.modded.ModdedWorkspaceGenerator;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.function.Function2;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.math.Spiraler;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.zip.ZipUtil;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class ModdedStudioCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final Pattern PROJECT_NAME = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern STUDIO_ID_SANITIZER = Pattern.compile("[^a-z0-9_-]");
    private static final String STUDIO_NAMESPACE = "irisworldgen";
    private static final String STUDIO_PREFIX = "studio_";
    private static final String DEFAULT_TEMPLATE = "example";
    private static final UUID CONSOLE_OWNER = new UUID(0L, 0L);
    private static final Map<UUID, String> STUDIOS = new ConcurrentHashMap<>();
    private static final SuggestionProvider<CommandSourceStack> GENERATOR_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = IrisModdedCommands.engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getGeneratorLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    };

    private ModdedStudioCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(GATE);

        root.executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name));

        root.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes((CommandContext<CommandSourceStack> context) -> create(context.getSource(), StringArgumentType.getString(context, "name"), DEFAULT_TEMPLATE))
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                                .executes((CommandContext<CommandSourceStack> context) -> create(context.getSource(), StringArgumentType.getString(context, "name"), StringArgumentType.getString(context, "template"))))));
        root.then(Commands.literal("+")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes((CommandContext<CommandSourceStack> context) -> create(context.getSource(), StringArgumentType.getString(context, "name"), DEFAULT_TEMPLATE))
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                                .executes((CommandContext<CommandSourceStack> context) -> create(context.getSource(), StringArgumentType.getString(context, "name"), StringArgumentType.getString(context, "template"))))));

        root.then(Commands.literal("package")
                .executes((CommandContext<CommandSourceStack> context) -> pkg(context.getSource(), null))
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> pkg(context.getSource(), StringArgumentType.getString(context, "pack")))));

        root.then(Commands.literal("version")
                .executes((CommandContext<CommandSourceStack> context) -> version(context.getSource(), null))
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> version(context.getSource(), StringArgumentType.getString(context, "pack")))));

        root.then(Commands.literal("regions")
                .executes((CommandContext<CommandSourceStack> context) -> regions(context.getSource(), 500))
                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 1000))
                        .executes((CommandContext<CommandSourceStack> context) -> regions(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))));

        root.then(openTree("open"));
        root.then(openTree("o"));
        root.then(Commands.literal("close")
                .executes((CommandContext<CommandSourceStack> context) -> close(context.getSource())));
        root.then(Commands.literal("x")
                .executes((CommandContext<CommandSourceStack> context) -> close(context.getSource())));
        root.then(Commands.literal("tpstudio")
                .executes((CommandContext<CommandSourceStack> context) -> tpStudio(context.getSource())));
        root.then(Commands.literal("stp")
                .executes((CommandContext<CommandSourceStack> context) -> tpStudio(context.getSource())));
        root.then(Commands.literal("status")
                .executes((CommandContext<CommandSourceStack> context) -> status(context.getSource())));
        root.then(workspaceTree("vscode", true));
        root.then(workspaceTree("vsc", true));
        root.then(workspaceTree("update", false));
        root.then(message("importvanilla", "Vanilla tree/object/structure capture generates features in throwaway Bukkit worlds via NMS; run /iris studio importvanilla on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("importv", "Vanilla tree/object/structure capture generates features in throwaway Bukkit worlds via NMS; run /iris studio importvanilla on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("iv", "Vanilla tree/object/structure capture generates features in throwaway Bukkit worlds via NMS; run /iris studio importvanilla on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(noiseTree("noise"));
        root.then(noiseTree("nmap"));
        root.then(mapTree("map"));
        root.then(mapTree("render"));
        root.then(message("loot", "Loot simulation opens a Bukkit chest inventory GUI; it is not available on modded servers."));
        root.then(message("profile", "Pack performance profiling is part of the Bukkit studio toolchain and is not ported to modded servers."));
        root.then(message("spawn", "Iris entity spawning uses the Bukkit entity pipeline and is not ported to modded servers."));
        root.then(message("summon", "Iris entity spawning uses the Bukkit entity pipeline and is not ported to modded servers."));
        root.then(message("objects", "The chunk object report reads Bukkit chunk data and is not ported to modded servers."));
        root.then(message("find-objects", "The chunk object report reads Bukkit chunk data and is not ported to modded servers."));

        return root;
    }

    public static void clear() {
        STUDIOS.clear();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> openTree(String name) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> openHelp(context.getSource()))
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> open(context.getSource(), StringArgumentType.getString(context, "pack"), 1337L))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes((CommandContext<CommandSourceStack> context) -> open(context.getSource(), StringArgumentType.getString(context, "pack"), LongArgumentType.getLong(context, "seed")))));
    }

    private static int openHelp(CommandSourceStack source) {
        IrisModdedCommands.fail(source, "Provide a dimension pack: /iris studio open <pack> [seed]");
        return 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> noiseTree(String name) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> noise(context.getSource(), null, 12345L))
                .then(Commands.argument("generator", StringArgumentType.word()).suggests(GENERATOR_KEYS)
                        .executes((CommandContext<CommandSourceStack> context) -> noise(context.getSource(), StringArgumentType.getString(context, "generator"), 12345L))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes((CommandContext<CommandSourceStack> context) -> noise(context.getSource(), StringArgumentType.getString(context, "generator"), LongArgumentType.getLong(context, "seed")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mapTree(String name) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> map(context.getSource()));
    }

    private static int noise(CommandSourceStack source, String generatorKey, long seed) {
        ServerLevel level = source.getLevel();
        Engine engine = IrisModdedCommands.engineFor(level);
        if (!GuiHost.isAvailable() || !IrisSettings.get().getGui().isUseServerLaunchedGuis()) {
            IrisModdedCommands.fail(source, guiUnavailableMessage());
            return 0;
        }
        if (engine != null) {
            ServerPlayer player = source.getPlayer();
            ModdedGuiHost.bindContext(source.getServer(), level, engine, player == null ? null : player.getUUID());
        }
        if (generatorKey == null || generatorKey.isBlank()) {
            NoiseExplorerGUI.launch();
            IrisModdedCommands.ok(source, "Opening the Noise Explorer on the server display.");
            return 1;
        }
        if (engine == null) {
            IrisModdedCommands.fail(source, "This dimension is not generated by Iris; run /iris studio noise from an Iris dimension to resolve generators, or omit the generator name.");
            return 0;
        }
        IrisGenerator generator = engine.getData().getGeneratorLoader().load(generatorKey.trim());
        if (generator == null) {
            IrisModdedCommands.fail(source, "Unknown generator '" + generatorKey + "' in pack " + engine.getDimension().getLoadKey() + ".");
            return 0;
        }
        long mixedSeed = new RNG(seed).nextParallelRNG(3245).lmax();
        Supplier<Function2<Double, Double, Double>> supplier = () -> (Double x, Double z) -> generator.getHeight(x, z, mixedSeed);
        NoiseExplorerGUI.launch(supplier, generatorKey.trim());
        IrisModdedCommands.ok(source, "Opening the Noise Explorer for generator '" + generatorKey.trim() + "' (seed " + seed + ").");
        return 1;
    }

    private static int map(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Engine engine = IrisModdedCommands.engineFor(level);
        if (engine == null) {
            IrisModdedCommands.fail(source, "This dimension is not generated by Iris; stand in an Iris (or studio) dimension and run /iris studio map.");
            return 0;
        }
        if (!GuiHost.isAvailable() || !IrisSettings.get().getGui().isUseServerLaunchedGuis()) {
            IrisModdedCommands.fail(source, guiUnavailableMessage());
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        ModdedGuiHost.bindContext(source.getServer(), level, engine, player == null ? null : player.getUUID());
        VisionGUI.launch(engine);
        IrisModdedCommands.ok(source, "Opening the Vision map for " + level.dimension().identifier() + " on the server display.");
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> workspaceTree(String name, boolean open) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> workspace(context.getSource(), null, open))
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(IrisModdedCommands.PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> workspace(context.getSource(), StringArgumentType.getString(context, "pack"), open)));
    }

    private static int workspace(CommandSourceStack source, String pack, boolean open) {
        File folder = resolvePack(source, pack);
        if (folder == null) {
            return 0;
        }
        File workspace;
        try {
            workspace = ModdedWorkspaceGenerator.writeWorkspace(IrisData.get(folder), folder);
        } catch (IOException e) {
            LOGGER.error("Iris workspace write failed for {}", folder, e);
            IrisModdedCommands.fail(source, "Failed to write workspace for " + folder.getAbsolutePath() + ": " + e.getMessage());
            return 0;
        }
        IrisModdedCommands.ok(source, "Workspace regenerated: " + workspace.getAbsolutePath() + " with JSON schemas for autocomplete.");
        if (!open) {
            return 1;
        }
        if (!GuiHost.isAvailable() || !Desktop.isDesktopSupported()) {
            IrisModdedCommands.fail(source, guiUnavailableMessage());
            return 0;
        }
        try {
            Desktop.getDesktop().open(workspace);
        } catch (Throwable e) {
            LOGGER.error("Iris workspace open failed for {}", workspace, e);
            IrisModdedCommands.fail(source, "Could not open " + workspace.getName() + ": " + e.getClass().getSimpleName());
            return 0;
        }
        IrisModdedCommands.ok(source, "Opening " + workspace.getName() + " in your editor.");
        return 1;
    }

    private static String guiUnavailableMessage() {
        if (GuiHost.isDesktopSuppressed()) {
            return "Iris desktop GUIs are disabled in singleplayer/client to avoid crashing the game client; use the in-game map and chat output instead.";
        }
        if (!GuiHost.isAvailable()) {
            return "This server has no display (headless JVM); the Iris desktop GUIs need an AWT-capable session.";
        }
        return "Server-launched GUIs are disabled (gui.useServerLaunchedGuis=false in Iris settings).";
    }

    private static LiteralArgumentBuilder<CommandSourceStack> message(String name, String text) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> {
                    IrisModdedCommands.fail(context.getSource(), text);
                    return 0;
                })
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes((CommandContext<CommandSourceStack> context) -> {
                            IrisModdedCommands.fail(context.getSource(), text);
                            return 0;
                        }));
    }

    private static File resolvePack(CommandSourceStack source, String pack) {
        String name = pack;
        if (name == null || name.isBlank()) {
            Engine engine = IrisModdedCommands.engineFor(source.getLevel());
            if (engine == null || engine.getDimension() == null) {
                IrisModdedCommands.fail(source, "This dimension is not generated by Iris; specify a pack name explicitly.");
                return null;
            }
            name = engine.getDimension().getLoadKey();
        }
        File folder = new File(ModdedPackCommands.packsRoot(), name);
        if (!folder.isDirectory() || !new File(folder, "dimensions").isDirectory()) {
            IrisModdedCommands.fail(source, "Pack '" + name + "' not found under " + ModdedPackCommands.packsRoot().getAbsolutePath());
            return null;
        }
        return folder;
    }

    private static String studioDimensionId(ServerPlayer player) {
        String base = STUDIO_ID_SANITIZER.matcher(player.getScoreboardName().toLowerCase(Locale.ROOT)).replaceAll("_");
        if (base.isBlank()) {
            base = player.getUUID().toString().replace("-", "");
        }
        return STUDIO_NAMESPACE + ":" + STUDIO_PREFIX + base;
    }

    private static String studioConsoleDimensionId() {
        return STUDIO_NAMESPACE + ":" + STUDIO_PREFIX + "console";
    }

    private static int open(CommandSourceStack source, String pack, long seed) {
        if (pack == null || pack.isBlank()) {
            IrisModdedCommands.fail(source, "Provide a dimension pack: /iris studio open <pack> [seed]");
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        UUID owner = player == null ? CONSOLE_OWNER : player.getUUID();
        String dimensionId = player == null ? studioConsoleDimensionId() : studioDimensionId(player);
        MinecraftServer server = source.getServer();
        IrisModdedCommands.ok(source, "Opening studio for '" + pack + "' (seed " + seed + ")...");
        Thread thread = new Thread(() -> openAsync(source, server, owner, dimensionId, pack, seed), "Iris Studio Open");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static void openAsync(CommandSourceStack source, MinecraftServer server, UUID owner, String dimensionId, String pack, long seed) {
        try {
            File packFolder = new File(ModdedPackCommands.packsRoot(), pack);
            if (!new File(packFolder, "dimensions/" + pack + ".json").isFile()) {
                server.execute(() -> IrisModdedCommands.ok(source, "Pack '" + pack + "' missing; downloading IrisDimensions/" + pack + "..."));
                boolean installed = ModdedPackInstaller.install(ModdedEngineBootstrap.loader().configDir(), pack, "master",
                        (String line) -> server.execute(() -> IrisModdedCommands.ok(source, line)));
                if (!installed || !new File(packFolder, "dimensions/" + pack + ".json").isFile()) {
                    server.execute(() -> IrisModdedCommands.fail(source, "Pack '" + pack + "' could not be downloaded; check the name and try /iris download " + pack + "."));
                    return;
                }
            }
            IrisData data = IrisData.get(packFolder);
            IrisDimension dimension = data.getDimensionLoader().load(pack);
            if (dimension == null) {
                server.execute(() -> IrisModdedCommands.fail(source, "Pack '" + pack + "' has no dimensions/" + pack + ".json"));
                return;
            }
            server.execute(() -> {
                if (owner.equals(CONSOLE_OWNER)) {
                    injectConsole(source, server, dimensionId, pack, seed);
                } else {
                    injectAndTeleport(source, server, owner, dimensionId, pack, seed);
                }
            });
        } catch (Throwable e) {
            LOGGER.error("Iris studio open failed for {}", pack, e);
            server.execute(() -> IrisModdedCommands.fail(source, "Studio open failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage())));
        }
    }

    private static void injectConsole(CommandSourceStack source, MinecraftServer server, String dimensionId, String pack, long seed) {
        ModdedDimensionManager.Handle handle;
        try {
            handle = ModdedDimensionManager.create(server, dimensionId, pack, pack, seed);
        } catch (Throwable e) {
            LOGGER.error("Iris console studio injection failed for {} ({})", dimensionId, pack, e);
            IrisModdedCommands.fail(source, "Studio injection failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return;
        }
        STUDIOS.put(CONSOLE_OWNER, dimensionId);
        ServerLevel studio = handle.level();
        int surface = studio.getMaxY();
        try {
            Engine engine = IrisModdedCommands.engineFor(studio);
            if (engine != null) {
                surface = engine.getMinHeight() + engine.getHeight(8, 8, false) + 2;
            }
        } catch (Throwable e) {
            LOGGER.error("Iris console studio surface probe failed for {}", dimensionId, e);
        }
        IrisModdedCommands.ok(source, "Console studio open: " + dimensionId + " now runs '" + pack + "' seed " + seed + " (transient; not written to iris-dimensions.json and cleared on restart).");
        IrisModdedCommands.ok(source, "Enter it with: /execute in " + dimensionId + " run tp @s 8.5 " + surface + " 8.5");
        IrisModdedCommands.ok(source, "Pregen it with: /iris pregen start <radius> " + dimensionId);
        IrisModdedCommands.ok(source, "Remove it with: /iris studio close");
    }

    private static void injectAndTeleport(CommandSourceStack source, MinecraftServer server, UUID owner, String dimensionId, String pack, long seed) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) {
            return;
        }
        ModdedDimensionManager.Handle handle;
        try {
            handle = ModdedDimensionManager.create(server, dimensionId, pack, pack, seed);
        } catch (Throwable e) {
            LOGGER.error("Iris studio injection failed for {} ({})", dimensionId, pack, e);
            IrisModdedCommands.fail(source, "Studio injection failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return;
        }
        STUDIOS.put(owner, dimensionId);
        ServerLevel studio = handle.level();
        int surface = studio.getMaxY();
        try {
            Engine engine = IrisModdedCommands.engineFor(studio);
            if (engine != null) {
                surface = engine.getMinHeight() + engine.getHeight(8, 8, false) + 2;
            }
        } catch (Throwable e) {
            LOGGER.error("Iris studio surface probe failed for {}", dimensionId, e);
        }
        player.teleportTo(studio, 8.5D, surface, 8.5D, java.util.Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        IrisModdedCommands.ok(source, "Studio open: " + dimensionId + " now runs '" + pack + "' seed " + seed + ". Use /iris studio close when done.");
    }

    private static int close(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        MinecraftServer server = source.getServer();
        UUID owner = player == null ? CONSOLE_OWNER : player.getUUID();
        String dimensionId = STUDIOS.remove(owner);
        if (dimensionId == null) {
            IrisModdedCommands.fail(source, "You do not have an open studio. Use /iris studio open <pack> first.");
            return 0;
        }
        try {
            ModdedDimensionManager.remove(server, dimensionId, true);
        } catch (Throwable e) {
            LOGGER.error("Iris studio close failed for {}", dimensionId, e);
            IrisModdedCommands.fail(source, "Studio close failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return 0;
        }
        IrisModdedCommands.ok(source, "Studio closed: " + dimensionId + " was evacuated, unloaded and its region data deleted.");
        return 1;
    }

    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        List<ModdedDimensionManager.Handle> handles = ModdedDimensionManager.handles();
        List<ModdedDimensionManager.Handle> studios = new ArrayList<>();
        for (ModdedDimensionManager.Handle handle : handles) {
            if (handle.dimensionId().startsWith(STUDIO_NAMESPACE + ":" + STUDIO_PREFIX) && ModdedDimensionManager.level(server, handle.dimensionId()) != null) {
                studios.add(handle);
            }
        }
        if (studios.isEmpty()) {
            IrisModdedCommands.ok(source, "No studio dimensions are currently open.");
            return 1;
        }
        IrisModdedCommands.ok(source, "Active studio dimension(s): " + studios.size());
        for (ModdedDimensionManager.Handle handle : studios) {
            UUID owner = ownerOf(handle.dimensionId());
            String ownerName = owner == null ? "unclaimed" : ownerName(server, owner);
            IrisModdedCommands.ok(source, "  " + handle.dimensionId() + ": pack '" + handle.pack() + "' seed " + handle.seed() + " owner " + ownerName);
        }
        return 1;
    }

    private static UUID ownerOf(String dimensionId) {
        for (Map.Entry<UUID, String> entry : STUDIOS.entrySet()) {
            if (entry.getValue().equals(dimensionId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String ownerName(MinecraftServer server, UUID owner) {
        if (owner.equals(CONSOLE_OWNER)) {
            return "console";
        }
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        return player == null ? owner.toString() : player.getScoreboardName();
    }

    private static int tpStudio(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players.");
            return 0;
        }
        MinecraftServer server = source.getServer();
        String dimensionId = STUDIOS.get(player.getUUID());
        if (dimensionId == null) {
            IrisModdedCommands.fail(source, "You do not have an open studio. Use /iris studio open <pack> first.");
            return 0;
        }
        ServerLevel studio = ModdedDimensionManager.level(server, dimensionId);
        if (studio == null) {
            STUDIOS.remove(player.getUUID());
            IrisModdedCommands.fail(source, "Your studio dimension is no longer loaded. Use /iris studio open <pack> again.");
            return 0;
        }
        int surface = studio.getMaxY();
        try {
            Engine engine = IrisModdedCommands.engineFor(studio);
            if (engine != null) {
                surface = engine.getMinHeight() + engine.getHeight(8, 8, false) + 2;
            }
        } catch (Throwable e) {
            LOGGER.error("Iris tpstudio surface probe failed", e);
        }
        player.teleportTo(studio, 8.5D, surface, 8.5D, java.util.Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        IrisModdedCommands.ok(source, "Teleported to your studio (" + dimensionId + ").");
        return 1;
    }

    private static int version(CommandSourceStack source, String pack) {
        File folder = resolvePack(source, pack);
        if (folder == null) {
            return 0;
        }
        IrisData data = IrisData.get(folder);
        IrisDimension dimension = data.getDimensionLoader().load(folder.getName());
        if (dimension == null) {
            IrisModdedCommands.fail(source, "Pack '" + folder.getName() + "' has no dimensions/" + folder.getName() + ".json");
            return 0;
        }
        IrisModdedCommands.ok(source, "The \"" + dimension.getName() + "\" pack has version: " + dimension.getVersion());
        return 1;
    }

    private static int create(CommandSourceStack source, String nameRaw, String template) {
        String name = nameRaw.toLowerCase(Locale.ROOT);
        if (!PROJECT_NAME.matcher(name).matches()) {
            IrisModdedCommands.fail(source, "Invalid project name '" + nameRaw + "' (allowed: a-z, 0-9, _ and -)");
            return 0;
        }
        File packsRoot = ModdedPackCommands.packsRoot();
        File target = new File(packsRoot, name);
        if (target.exists()) {
            IrisModdedCommands.fail(source, "Pack '" + name + "' already exists at " + target.getAbsolutePath());
            return 0;
        }
        MinecraftServer server = source.getServer();
        IrisModdedCommands.ok(source, "Creating project '" + name + "' from template '" + template + "'...");
        Thread thread = new Thread(() -> {
            try {
                File templateFolder = new File(packsRoot, template);
                if (!new File(templateFolder, "dimensions/" + template + ".json").isFile()) {
                    server.execute(() -> IrisModdedCommands.ok(source, "Template '" + template + "' is not installed; downloading IrisDimensions/" + template + "..."));
                    boolean installed = ModdedPackInstaller.install(ModdedEngineBootstrap.loader().configDir(), template, "master",
                            (String line) -> server.execute(() -> IrisModdedCommands.ok(source, line)));
                    if (!installed || !new File(templateFolder, "dimensions/" + template + ".json").isFile()) {
                        server.execute(() -> IrisModdedCommands.fail(source, "Template '" + template + "' could not be downloaded; install a pack with dimensions/" + template + ".json first."));
                        return;
                    }
                }
                IrisProjectCopier.copyProject(templateFolder, target, template, name);
                try {
                    ModdedWorkspaceGenerator.writeWorkspace(IrisData.get(target), target);
                } catch (IOException e) {
                    LOGGER.error("Iris studio create workspace generation failed for {}", name, e);
                }
                server.execute(() -> {
                    IrisModdedCommands.ok(source, "Created project '" + name + "' at " + target.getAbsolutePath());
                    IrisModdedCommands.ok(source, "Edit dimensions/" + name + ".json and the rest of the pack. A VSCode workspace with JSON schema autocomplete was generated; open it or rerun /iris studio vscode.");
                });
            } catch (Throwable e) {
                LOGGER.error("Iris studio create failed for {}", name, e);
                server.execute(() -> IrisModdedCommands.fail(source, "Project creation failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage())));
            }
        }, "Iris Studio Create");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int pkg(CommandSourceStack source, String pack) {
        File folder = resolvePack(source, pack);
        if (folder == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        String dimKey = folder.getName();
        IrisModdedCommands.ok(source, "Packaging dimension '" + dimKey + "'...");
        Thread thread = new Thread(() -> {
            try {
                File result = compilePackage(folder, dimKey);
                server.execute(() -> IrisModdedCommands.ok(source, "Package compiled: " + result.getAbsolutePath()));
            } catch (Throwable e) {
                LOGGER.error("Iris package failed for {}", dimKey, e);
                server.execute(() -> IrisModdedCommands.fail(source, "Packaging failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage())));
            }
        }, "Iris Studio Package");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static File compilePackage(File packFolder, String dimKey) throws IOException {
        IrisData dm = IrisData.get(packFolder);
        IrisDimension dimension = dm.getDimensionLoader().load(dimKey);
        if (dimension == null) {
            throw new IOException("Pack '" + dimKey + "' has no dimensions/" + dimKey + ".json");
        }
        File exports = ModdedEngineBootstrap.loader().configDir().resolve("irisworldgen").resolve("exports").toFile();
        File folder = new File(exports, dimension.getLoadKey());
        IO.delete(folder);
        folder.mkdirs();

        LinkedHashSet<String> regionKeys = new LinkedHashSet<>();
        LinkedHashSet<String> biomeKeys = new LinkedHashSet<>();
        LinkedHashSet<String> entityKeys = new LinkedHashSet<>();
        LinkedHashSet<String> spawnerKeys = new LinkedHashSet<>();
        LinkedHashSet<String> generatorKeys = new LinkedHashSet<>();
        LinkedHashSet<String> lootKeys = new LinkedHashSet<>();
        LinkedHashSet<String> objectKeys = new LinkedHashSet<>();
        LinkedHashSet<String> structureKeys = new LinkedHashSet<>();

        regionKeys.addAll(dimension.getRegions());
        lootKeys.addAll(dimension.getLoot().getTables());
        spawnerKeys.addAll(dimension.getEntitySpawners());
        collectStructureKeys(structureKeys, dimension.getStructures());

        for (String regionKey : regionKeys) {
            IrisRegion region = dm.getRegionLoader().load(regionKey);
            if (region == null) {
                continue;
            }
            region.getAllBiomes(() -> dm).forEach((IrisBiome biome) -> {
                if (biome != null && biome.getLoadKey() != null) {
                    biomeKeys.add(biome.getLoadKey());
                }
            });
            lootKeys.addAll(region.getLoot().getTables());
            spawnerKeys.addAll(region.getEntitySpawners());
            collectStructureKeys(structureKeys, region.getStructures());
        }
        for (String biomeKey : biomeKeys) {
            IrisBiome biome = dm.getBiomeLoader().load(biomeKey);
            if (biome == null) {
                continue;
            }
            biome.getGenerators().forEach((IrisBiomeGeneratorLink link) -> generatorKeys.add(link.getGenerator()));
            lootKeys.addAll(biome.getLoot().getTables());
            spawnerKeys.addAll(biome.getEntitySpawners());
            collectStructureKeys(structureKeys, biome.getStructures());
            for (IrisObjectPlacement placement : biome.getObjects()) {
                objectKeys.addAll(placement.getPlace());
            }
        }
        for (String spawnerKey : spawnerKeys) {
            IrisSpawner spawner = dm.getSpawnerLoader().load(spawnerKey);
            if (spawner == null) {
                continue;
            }
            spawner.getSpawns().forEach((IrisEntitySpawn spawn) -> entityKeys.add(spawn.getEntity()));
        }

        StringBuilder hashes = new StringBuilder();
        StructurePackageClosure structureClosure = StructurePackageClosure.collect(packFolder, structureKeys);
        if (!structureClosure.isValid()) {
            throw new IOException("Structure package closure is invalid: " + String.join("; ", structureClosure.errors()));
        }
        hashes.append(structureClosure.writeTo(folder, true));
        for (String objectKey : objectKeys) {
            try {
                File objectFile = dm.getObjectLoader().findFile(objectKey);
                IO.copyFile(objectFile, new File(folder, "objects/" + objectKey + ".iob"));
                hashes.append(IO.hash(objectFile));
            } catch (Throwable e) {
                LOGGER.error("Iris package failed to copy object {}", objectKey, e);
            }
        }

        hashes.append(copyJson(folder, "dimensions", dimension.getLoadKey(), dm.getDimensionLoader().findFile(dimension.getLoadKey())));
        for (String key : generatorKeys) {
            hashes.append(copyJson(folder, "generators", key, dm.getGeneratorLoader().findFile(key)));
        }
        for (String key : regionKeys) {
            hashes.append(copyJson(folder, "regions", key, dm.getRegionLoader().findFile(key)));
        }
        for (String key : dm.getBlockLoader().getPossibleKeys()) {
            hashes.append(copyJson(folder, "blocks", key, dm.getBlockLoader().findFile(key)));
        }
        for (String key : biomeKeys) {
            hashes.append(copyJson(folder, "biomes", key, dm.getBiomeLoader().findFile(key)));
        }
        for (String key : entityKeys) {
            hashes.append(copyJson(folder, "entities", key, dm.getEntityLoader().findFile(key)));
        }
        for (String key : lootKeys) {
            hashes.append(copyJson(folder, "loot", key, dm.getLootLoader().findFile(key)));
        }

        JSONObject meta = new JSONObject();
        meta.put("hash", IO.hash(hashes.toString()));
        meta.put("time", M.ms());
        meta.put("version", dimension.getVersion());
        IO.writeAll(new File(folder, "package.json"), meta.toString(0));

        File output = new File(exports, dimension.getLoadKey() + ".iris");
        ZipUtil.pack(folder, output, 9);
        IO.delete(folder);
        return output;
    }

    private static void collectStructureKeys(LinkedHashSet<String> keys, List<IrisStructurePlacement> placements) {
        if (placements == null) {
            return;
        }
        for (IrisStructurePlacement placement : placements) {
            if (placement == null || placement.getStructures() == null) {
                continue;
            }
            for (String structureKey : placement.getStructures()) {
                keys.add(structureKey);
            }
        }
    }

    private static String copyJson(File folder, String category, String key, File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            String json = new JSONObject(IO.readAll(file)).toString(0);
            IO.writeAll(new File(folder, category + "/" + key + ".json"), json);
            return IO.hash(json);
        } catch (Throwable e) {
            LOGGER.error("Iris package failed to write {}/{}", category, key, e);
            return "";
        }
    }

    private static int regions(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players (sampling is centered on your position).");
            return 0;
        }
        Engine engine = IrisModdedCommands.engineFor(source.getLevel());
        if (engine == null) {
            IrisModdedCommands.fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        MinecraftServer server = source.getServer();
        int blockX = player.blockPosition().getX();
        int blockZ = player.blockPosition().getZ();
        IrisModdedCommands.ok(source, "Sampling region distribution in " + (radius * 2) + "x" + (radius * 2) + " chunks around you...");
        Thread thread = new Thread(() -> {
            try {
                int diameter = radius * 2;
                int totalTasks = diameter * diameter;
                KMap<String, AtomicInteger> counts = new KMap<>();
                engine.getDimension().getRegions().forEach((String key) -> counts.put(key, new AtomicInteger(0)));
                MultiBurst burst = new MultiBurst("Region Sampler");
                BurstExecutor executor = burst.burst(totalTasks);
                new Spiraler(diameter, diameter, (int x, int z) -> executor.queue(() -> {
                    IrisRegion region = engine.getRegion((x << 4) + 8, (z << 4) + 8);
                    counts.computeIfAbsent(region.getLoadKey(), (String key) -> new AtomicInteger(0)).incrementAndGet();
                })).setOffset(blockX >> 4, blockZ >> 4).drain();
                executor.complete();
                burst.close();
                server.execute(() -> counts.forEach((String key, AtomicInteger count) -> {
                    IrisRegion region = engine.getData().getRegionLoader().load(key);
                    String rarity = region == null ? "?" : String.valueOf(region.getRarity());
                    IrisModdedCommands.ok(source, key + ": rarity=" + rarity + " / " + Form.f((double) count.get() / totalTasks * 100, 2) + "%");
                }));
            } catch (Throwable e) {
                LOGGER.error("Iris region sampling failed", e);
                server.execute(() -> IrisModdedCommands.fail(source, "Region sampling failed: " + e.getClass().getSimpleName()));
            }
        }, "Iris Region Sampler");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }
}
