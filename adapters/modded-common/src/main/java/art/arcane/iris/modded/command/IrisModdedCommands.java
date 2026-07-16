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
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.Locator;
import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.framework.WrongEngineBroException;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
import art.arcane.iris.engine.object.IrisPosition;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedBlockState;
import art.arcane.iris.modded.ModdedDimensionManager;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedLoader;
import art.arcane.iris.modded.ModdedPackInstaller;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.volmlib.util.matter.MatterMarker;
import com.mojang.datafixers.util.Pair;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

public final class IrisModdedCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final long LOCATE_TIMEOUT_MS = 120000L;
    private static final int NATIVE_STRUCTURE_LOCATE_RADIUS = 100;

    private static final SuggestionProvider<CommandSourceStack> BIOME_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestBiomeKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> REGION_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestRegionKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> OBJECT_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestObjectKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> STRUCTURE_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestStructureKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> POI_TYPES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> SharedSuggestionProvider.suggest(List.of("buried_treasure"), builder);
    private static final SuggestionProvider<CommandSourceStack> MARKER_TYPES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> SharedSuggestionProvider.suggest(List.of("cave_floor", "cave_ceiling", "object"), builder);
    private static final DustParticleOptions MARKER_DUST = new DustParticleOptions(0x5A8CFF, 1.2F);
    static final SuggestionProvider<CommandSourceStack> PACK_NAMES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestPackNames(context, builder);
    private static final SuggestionProvider<CommandSourceStack> DIMENSION_NAMES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestDimensionNames(context, builder);

    private IrisModdedCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(rootTree());
        dispatcher.register(Commands.literal("ir").redirect(root));
        dispatcher.register(Commands.literal("irs").redirect(root));
        IrisLogging.info("Iris /iris command tree registered");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rootTree() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("iris");

        root.executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), ""));
        root.then(helpTree());

        root.then(Commands.literal("version")
                .executes((CommandContext<CommandSourceStack> context) -> version(context.getSource())));

        root.then(Commands.literal("info").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> info(context.getSource(), null))
                .then(Commands.argument("dimension", StringArgumentType.greedyString()).suggests(DIMENSION_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> info(context.getSource(), StringArgumentType.getString(context, "dimension")))));

        root.then(Commands.literal("what").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> what(context.getSource()))
                .then(Commands.literal("block")
                        .executes((CommandContext<CommandSourceStack> context) -> whatBlock(context.getSource())))
                .then(Commands.literal("hand")
                        .executes((CommandContext<CommandSourceStack> context) -> whatHand(context.getSource())))
                .then(Commands.literal("markers")
                        .then(Commands.argument("marker", StringArgumentType.greedyString()).suggests(MARKER_TYPES)
                                .executes((CommandContext<CommandSourceStack> context) -> whatMarkers(context.getSource(), StringArgumentType.getString(context, "marker"))))));

        root.then(Commands.literal("tp").requires(GATE)
                .then(Commands.argument("dimension", DimensionArgument.dimension()).suggests(DIMENSION_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> tp(context.getSource(), DimensionArgument.getDimension(context, "dimension"), null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes((CommandContext<CommandSourceStack> context) -> tp(context.getSource(), DimensionArgument.getDimension(context, "dimension"), EntityArgument.getPlayer(context, "player"))))));

        root.then(Commands.literal("evacuate").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> evacuate(context.getSource(), null))
                .then(Commands.argument("dimension", DimensionArgument.dimension()).suggests(DIMENSION_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> evacuate(context.getSource(), DimensionArgument.getDimension(context, "dimension")))));

        root.then(Commands.literal("debug").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> debug(context.getSource())));

        root.then(Commands.literal("reload").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> reload(context.getSource())));

        root.then(gotoTree("goto"));
        root.then(gotoTree("find"));

        root.then(Commands.literal("seed").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> seed(context.getSource())));

        root.then(goldenhashTree("goldenhash"));
        root.then(goldenhashTree("gold"));

        root.then(downloadTree("download"));
        root.then(downloadTree("dl"));

        root.then(metricsTree("metrics"));
        root.then(metricsTree("measure"));

        root.then(regenTree("regen"));
        root.then(regenTree("rg"));

        root.then(pregenTree("pregen"));
        root.then(pregenTree("pregenerate"));

        root.then(Commands.literal("wand").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedObjectCommands.giveWand(context.getSource())));
        root.then(ModdedObjectCommands.tree("object"));
        root.then(ModdedObjectCommands.tree("o"));
        root.then(editTree());

        root.then(createTree());

        root.then(ModdedStudioCommands.tree("studio"));
        root.then(ModdedStudioCommands.tree("std"));
        root.then(ModdedStudioCommands.tree("s"));
        root.then(ModdedPackCommands.tree("pack"));
        root.then(ModdedPackCommands.tree("pk"));
        root.then(ModdedWorldCommands.tree("world"));
        root.then(ModdedWorldCommands.tree("w"));
        root.then(ModdedDatapackCommands.tree("datapack"));
        root.then(ModdedDatapackCommands.tree("datapacks"));
        root.then(ModdedDatapackCommands.tree("dp"));
        root.then(ModdedStructureCommands.tree("structure"));
        root.then(ModdedStructureCommands.tree("struct"));
        root.then(ModdedStructureCommands.tree("str"));
        root.then(ModdedDeveloperCommands.tree("developer"));
        root.then(ModdedDeveloperCommands.tree("dev"));

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createTree() {
        return Commands.literal("create").requires(GATE)
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("pack", StringArgumentType.string()).suggests(PACK_NAMES)
                                .executes((CommandContext<CommandSourceStack> context) -> ModdedWorldCommands.createWorld(context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        StringArgumentType.getString(context, "pack"),
                                        1337L))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes((CommandContext<CommandSourceStack> context) -> ModdedWorldCommands.createWorld(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "pack"),
                                                LongArgumentType.getLong(context, "seed"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> helpTree() {
        return Commands.literal("help")
                .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), ""))
                .then(Commands.argument("section", StringArgumentType.greedyString())
                        .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), StringArgumentType.getString(context, "section"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> downloadTree(String name) {
        return Commands.literal(name).requires(GATE)
                .then(Commands.argument("pack", StringArgumentType.word()).suggests(PACK_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> download(context.getSource(), StringArgumentType.getString(context, "pack"), "stable"))
                        .then(Commands.argument("branch", StringArgumentType.word())
                                .executes((CommandContext<CommandSourceStack> context) -> download(context.getSource(), StringArgumentType.getString(context, "pack"), StringArgumentType.getString(context, "branch")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> metricsTree(String name) {
        return Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> metrics(context.getSource()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> regenTree(String name) {
        return Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> regen(context.getSource(), 0))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 64))
                        .executes((CommandContext<CommandSourceStack> context) -> regen(context.getSource(), IntegerArgumentType.getInteger(context, "radius"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> gotoTree(String name) {
        return Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name))
                .then(Commands.literal("biome")
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(BIOME_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoBiome(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("region")
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(REGION_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoRegion(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("object")
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoObject(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("structure")
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(STRUCTURE_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoStructure(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("poi")
                        .then(Commands.argument("type", StringArgumentType.greedyString()).suggests(POI_TYPES)
                                .executes((CommandContext<CommandSourceStack> context) -> gotoPoi(context.getSource(), StringArgumentType.getString(context, "type")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pregenTree(String name) {
        RequiredArgumentBuilder<CommandSourceStack, Integer> radius = Commands.argument("radius", IntegerArgumentType.integer(1, 100000))
                .executes((CommandContext<CommandSourceStack> context) -> pregenStart(context, false, false, false, false, false));
        attachPregenCenter(radius, false);
        attachPregenFlags(radius, false, false, false, false, false);
        RequiredArgumentBuilder<CommandSourceStack, Identifier> dimension = Commands.argument("dimension", DimensionArgument.dimension()).suggests(DIMENSION_NAMES)
                .executes((CommandContext<CommandSourceStack> context) -> pregenStart(context, true, false, false, false, false));
        attachPregenCenter(dimension, true);
        attachPregenFlags(dimension, true, false, false, false, false);
        radius.then(dimension);

        return Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name))
                .then(Commands.literal("start")
                        .then(radius))
                .then(Commands.literal("stop")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenStop(context.getSource())))
                .then(Commands.literal("x")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenStop(context.getSource())))
                .then(Commands.literal("pause")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenPause(context.getSource())))
                .then(Commands.literal("resume")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenPause(context.getSource())))
                .then(Commands.literal("status")
                        .executes((CommandContext<CommandSourceStack> context) -> pregenStatus(context.getSource())));
    }

    private static void attachPregenCenter(ArgumentBuilder<CommandSourceStack, ?> node, boolean withDimension) {
        RequiredArgumentBuilder<CommandSourceStack, Integer> z = Commands.argument("z", IntegerArgumentType.integer())
                .executes((CommandContext<CommandSourceStack> context) -> pregenStart(context, withDimension, true, false, false, false));
        attachPregenFlags(z, withDimension, true, false, false, false);
        node.then(Commands.literal("at")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(z)));
    }

    private static void attachPregenFlags(ArgumentBuilder<CommandSourceStack, ?> node, boolean withDimension, boolean withCenter, boolean gui, boolean sync, boolean nocache) {
        if (!gui) {
            node.then(pregenFlagNode("gui", withDimension, withCenter, true, sync, nocache));
        }
        if (!sync) {
            node.then(pregenFlagNode("sync", withDimension, withCenter, gui, true, nocache));
        }
        if (!nocache) {
            node.then(pregenFlagNode("nocache", withDimension, withCenter, gui, sync, true));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pregenFlagNode(String name, boolean withDimension, boolean withCenter, boolean gui, boolean sync, boolean nocache) {
        LiteralArgumentBuilder<CommandSourceStack> flag = Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> pregenStart(context, withDimension, withCenter, gui, sync, nocache));
        attachPregenFlags(flag, withDimension, withCenter, gui, sync, nocache);
        return flag;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> goldenhashTree(String name) {
        LiteralArgumentBuilder<CommandSourceStack> radiusAndThreads = Commands.literal(name).requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), 8, 8, ModdedGoldenHash.Mode.AUTO));
        attachModes(radiusAndThreads, (CommandContext<CommandSourceStack> context) -> 8, (CommandContext<CommandSourceStack> context) -> 8);

        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> radius = Commands.argument("radius", IntegerArgumentType.integer(0, 256))
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), 8, ModdedGoldenHash.Mode.AUTO));
        attachModes(radius, (CommandContext<CommandSourceStack> context) -> IntegerArgumentType.getInteger(context, "radius"), (CommandContext<CommandSourceStack> context) -> 8);

        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> threads = Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "threads"), ModdedGoldenHash.Mode.AUTO));
        attachModes(threads, (CommandContext<CommandSourceStack> context) -> IntegerArgumentType.getInteger(context, "radius"), (CommandContext<CommandSourceStack> context) -> IntegerArgumentType.getInteger(context, "threads"));

        radius.then(threads);
        radiusAndThreads.then(radius);
        return radiusAndThreads;
    }

    private interface IntExtractor {
        int extract(CommandContext<CommandSourceStack> context);
    }

    private static void attachModes(com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> node, IntExtractor radius, IntExtractor threads) {
        node.then(Commands.literal("capture")
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), radius.extract(context), threads.extract(context), ModdedGoldenHash.Mode.CAPTURE)));
        node.then(Commands.literal("verify")
                .executes((CommandContext<CommandSourceStack> context) -> goldenhash(context.getSource(), radius.extract(context), threads.extract(context), ModdedGoldenHash.Mode.VERIFY)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> editTree() {
        return Commands.literal("edit").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), "edit"))
                .then(Commands.literal("biome")
                        .executes((CommandContext<CommandSourceStack> context) -> editBiome(context.getSource(), null))
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(BIOME_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> editBiome(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("region")
                        .executes((CommandContext<CommandSourceStack> context) -> editRegion(context.getSource(), null))
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(REGION_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> editRegion(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("dimension")
                        .executes((CommandContext<CommandSourceStack> context) -> editDimension(context.getSource())));
    }

    private static int editBiome(CommandSourceStack source, String key) {
        Engine engine = engineFor(source.getLevel());
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        IrisBiome biome;
        if (key == null || key.isBlank()) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                fail(source, "Console must name a biome: /iris edit biome <key>");
                return 0;
            }
            BlockPos pos = player.blockPosition();
            try {
                biome = engine.getBiome(pos.getX(), pos.getY() - engine.getMinHeight(), pos.getZ());
            } catch (Throwable e) {
                fail(source, "Biome lookup failed: " + e.getClass().getSimpleName());
                return 0;
            }
        } else {
            biome = engine.getData().getBiomeLoader().load(key.trim());
            if (biome == null) {
                fail(source, "Unknown biome: " + key);
                return 0;
            }
        }
        return openJson(source, biome);
    }

    private static int editRegion(CommandSourceStack source, String key) {
        Engine engine = engineFor(source.getLevel());
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        IrisRegion region;
        if (key == null || key.isBlank()) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                fail(source, "Console must name a region: /iris edit region <key>");
                return 0;
            }
            BlockPos pos = player.blockPosition();
            try {
                region = engine.getRegion(pos.getX(), pos.getZ());
            } catch (Throwable e) {
                fail(source, "Region lookup failed: " + e.getClass().getSimpleName());
                return 0;
            }
        } else {
            region = engine.getData().getRegionLoader().load(key.trim());
            if (region == null) {
                fail(source, "Unknown region: " + key);
                return 0;
            }
        }
        return openJson(source, region);
    }

    private static int editDimension(CommandSourceStack source) {
        Engine engine = engineFor(source.getLevel());
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        return openJson(source, engine.getDimension());
    }

    private static int openJson(CommandSourceStack source, IrisRegistrant registrant) {
        if (!GuiHost.isAvailable() || !Desktop.isDesktopSupported()) {
            fail(source, "Cannot open files here: " + ModdedGuiHost.guiUnavailableReason());
            return 0;
        }
        if (registrant == null || registrant.getLoadFile() == null || !registrant.getLoadFile().isFile()) {
            fail(source, "Cannot find the file; perhaps it was not loaded directly from a file?");
            return 0;
        }
        File file = registrant.getLoadFile();
        try {
            Desktop.getDesktop().open(file);
        } catch (Throwable e) {
            LOGGER.error("Iris edit failed to open {}", file, e);
            fail(source, "Could not open " + file.getName() + ": " + e.getClass().getSimpleName());
            return 0;
        }
        ok(source, "Opening " + registrant.getTypeName() + " " + file.getName() + " in your editor.");
        return 1;
    }

    private static int tp(CommandSourceStack source, ServerLevel level, ServerPlayer target) {
        ServerPlayer player = target != null ? target : source.getPlayer();
        if (player == null) {
            fail(source, "Console must name a player: /iris tp <dimension> <player>");
            return 0;
        }
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator)) {
            fail(source, level.dimension().identifier() + " is not generated by Iris.");
            return 0;
        }
        String dimensionId = level.dimension().identifier().toString();
        if (!ModdedDimensionManager.teleport(player, source.getServer(), dimensionId, 8.5D, Double.MIN_VALUE, 8.5D)) {
            fail(source, "Teleport failed: dimension " + dimensionId + " is not loaded.");
            return 0;
        }
        ok(source, "Teleporting " + player.getScoreboardName() + " to " + dimensionId + "...");
        return 1;
    }

    private static int evacuate(CommandSourceStack source, ServerLevel target) {
        MinecraftServer server = source.getServer();
        ServerLevel level = target != null ? target : source.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator)) {
            fail(source, level.dimension().identifier() + " is not generated by Iris.");
            return 0;
        }
        ServerLevel fallback = server.overworld();
        if (fallback == level) {
            fail(source, "Cannot evacuate the primary world; there is nowhere to send players.");
            return 0;
        }
        int count = ModdedDimensionManager.evacuate(server, level);
        ok(source, "Evacuated " + count + " player(s) from " + level.dimension().identifier() + " to " + fallback.dimension().identifier() + ".");
        return 1;
    }

    private static int debug(CommandSourceStack source) {
        boolean to = !IrisSettings.get().getGeneral().isDebug();
        IrisSettings.get().getGeneral().setDebug(to);
        IrisSettings.get().forceSave();
        ok(source, "Set debug to: " + to);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        if (IrisSettings.settings != null) {
            IrisSettings.invalidate();
        }
        IrisSettings.get();
        ok(source, "Hotloaded settings");
        return 1;
    }

    private static int whatHand(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players (it inspects your held item).");
            return 0;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            fail(source, "Your main hand is empty.");
            return 0;
        }
        ok(source, "Hand: " + BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount());
        return 1;
    }

    private static int regen(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator irisGenerator)) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        ModdedRegen.start(source, level, irisGenerator, engine, player, radius);
        return 1;
    }

    private static int pregenStart(CommandContext<CommandSourceStack> context, boolean withDimension, boolean withCenter, boolean gui, boolean sync, boolean nocache) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        int radius = IntegerArgumentType.getInteger(context, "radius");
        int centerX = withCenter ? IntegerArgumentType.getInteger(context, "x") : 0;
        int centerZ = withCenter ? IntegerArgumentType.getInteger(context, "z") : 0;
        ServerLevel level = withDimension ? DimensionArgument.getDimension(context, "dimension") : source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            if (withDimension) {
                fail(source, level.dimension().identifier() + " is not generated by Iris; see /iris info for loaded Iris dimensions.");
            } else {
                fail(source, "The current dimension (" + level.dimension().identifier() + ") is not generated by Iris. Name one explicitly: /iris pregen start " + radius + " <dimension>; see /iris info for loaded Iris dimensions.");
            }
            return 0;
        }
        boolean showGui = gui && ModdedGuiHost.isGuiLaunchable();
        if (!ModdedPregenJob.start(source.getServer(), level, engine, radius, centerX, centerZ, showGui, sync, !nocache)) {
            fail(source, "A pregeneration task is already running. Stop it first with /iris pregen stop.");
            return 0;
        }
        ModdedPregenBossBar.begin(source.getPlayer());
        String guiNote;
        if (!gui) {
            guiNote = "";
        } else if (showGui) {
            guiNote = " A progress map window is opening on the server display.";
        } else {
            guiNote = " (GUI requested but unavailable: " + ModdedGuiHost.guiUnavailableReason() + ")";
        }
        String modeNote = " Mode: " + (sync ? "sync" : "async") + (nocache ? ", cache disabled." : ", resumable (checkpoint cache).");
        ok(source, "Pregen started in " + level.dimension().identifier() + " of " + (radius * 2) + " by " + (radius * 2)
                + " blocks from " + centerX + "," + centerZ + "." + modeNote + " Progress logs to console; see /iris pregen status." + guiNote);
        return 1;
    }

    private static int pregenStop(CommandSourceStack source) {
        if (ModdedPregenJob.stop()) {
            ModdedPregenBossBar.clear();
            ok(source, "Stopping pregeneration; finishing up the current region...");
            return 1;
        }
        fail(source, "No active pregeneration task to stop.");
        return 0;
    }

    private static int pregenPause(CommandSourceStack source) {
        Boolean paused = ModdedPregenJob.pauseResume();
        if (paused == null) {
            fail(source, "No active pregeneration task to pause/resume.");
            return 0;
        }
        ok(source, "Pregeneration is now " + (paused.booleanValue() ? "paused" : "running") + ".");
        return 1;
    }

    private static int pregenStatus(CommandSourceStack source) {
        Component status = ModdedPregenJob.statusComponent();
        if (status == null) {
            fail(source, "No active pregeneration task.");
            return 0;
        }
        ok(source, status);
        return 1;
    }

    private static int version(CommandSourceStack source) {
        ModdedLoader loader = ModdedEngineBootstrap.loader();
        int engines = engineCount(source.getServer());
        ok(source, "Iris " + loader.modVersion() + " by Volmit Software on " + loader.platformName()
                + " (Minecraft " + loader.minecraftVersion() + "), " + engines + " Iris dimension(s)");
        return 1;
    }

    private static int info(CommandSourceStack source, String filter) {
        MinecraftServer server = source.getServer();
        List<String> lines = new ArrayList<>();
        int total = 0;
        int iris = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total++;
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (!(generator instanceof IrisModdedChunkGenerator irisGenerator)) {
                continue;
            }
            iris++;
            String dimensionId = level.dimension().identifier().toString();
            if (filter != null && !dimensionId.contains(filter) && !irisGenerator.dimensionKey().contains(filter)) {
                continue;
            }
            Engine engine = irisGenerator.engineIfBound();
            if (engine == null) {
                lines.add(dimensionId + ": pack=" + irisGenerator.dimensionKey() + " (engine not started yet)");
                continue;
            }
            lines.add(dimensionId + ": pack=" + engine.getDimension().getLoadKey()
                    + " seed=" + level.getSeed()
                    + " height=" + engine.getMinHeight() + ".." + engine.getMaxHeight()
                    + " generated=" + engine.getGenerated()
                    + " data=" + engine.getData().getDataFolder().getAbsolutePath());
        }
        ok(source, "Loaded dimensions: " + total + " (" + iris + " Iris)");
        if (lines.isEmpty()) {
            ok(source, filter == null ? "No Iris dimensions are loaded." : "No Iris dimension matches '" + filter + "'.");
            return 0;
        }
        for (String line : lines) {
            ok(source, line);
        }
        return 1;
    }

    private static int what(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        BlockPos pos = player.blockPosition();
        int relativeY = pos.getY() - engine.getMinHeight();
        try {
            IrisBiome biome = engine.getBiome(pos.getX(), relativeY, pos.getZ());
            ok(source, "Biome: " + biome.getLoadKey() + " (" + biome.getName() + ")");
        } catch (Throwable e) {
            fail(source, "Biome lookup failed: " + e.getClass().getSimpleName());
        }
        try {
            IrisRegion region = engine.getRegion(pos.getX(), pos.getZ());
            ok(source, "Region: " + region.getLoadKey() + " (" + region.getName() + ")");
        } catch (Throwable e) {
            fail(source, "Region lookup failed: " + e.getClass().getSimpleName());
        }
        try {
            IrisBiome cave = engine.getCaveBiome(pos.getX(), relativeY, pos.getZ());
            ok(source, "Cave biome: " + (cave == null ? "none" : cave.getLoadKey()));
        } catch (Throwable e) {
            fail(source, "Cave biome lookup failed: " + e.getClass().getSimpleName());
        }
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        BlockState surface = level.getBlockState(new BlockPos(pos.getX(), surfaceY - 1, pos.getZ()));
        ok(source, "Surface block: " + BuiltInRegistries.BLOCK.getKey(surface.getBlock()) + " (y=" + (surfaceY - 1) + ")");
        ok(source, "Position: " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (chunk " + (pos.getX() >> 4) + "," + (pos.getZ() >> 4) + ")");
        return 1;
    }

    private static int whatBlock(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players (it inspects the block you are looking at).");
            return 0;
        }
        HitResult hit = player.pick(128.0D, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            fail(source, "Look at a block, not the sky.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        PlatformBlockState platform = ModdedBlockState.of(state, null);
        ok(source, "Block: " + platform.key() + " (y=" + pos.getY() + ")");
        List<String> flags = new ArrayList<>();
        if (platform.isSolid()) {
            flags.add("solid");
        }
        if (platform.isFluid()) {
            flags.add("fluid");
        }
        if (platform.isWater()) {
            flags.add("water");
        }
        if (platform.isWaterLogged()) {
            flags.add("waterlogged");
        }
        if (platform.isStorage()) {
            flags.add("storage (loot capable)");
        }
        if (platform.isLit()) {
            flags.add("lit");
        }
        if (platform.isFoliage()) {
            flags.add("foliage");
        }
        if (platform.isFoliagePlantable()) {
            flags.add("plantable foliage");
        }
        if (platform.isDecorant()) {
            flags.add("decorant");
        }
        if (platform.isOre()) {
            flags.add("ore");
        }
        if (platform.hasTileEntity()) {
            flags.add("tile entity");
        }
        ok(source, flags.isEmpty() ? "Properties: (none)" : "Properties: " + String.join(", ", flags));
        return 1;
    }

    private static int whatMarkers(CommandSourceStack source, String markerRaw) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players (markers render as particles around you).");
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        String marker = markerRaw.trim();
        BlockPos origin = player.blockPosition();
        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;
        MinecraftServer server = source.getServer();
        ok(source, "Scanning for '" + marker + "' markers around you...");
        Thread thread = new Thread(() -> {
            List<int[]> hits = new ArrayList<>();
            MatterMarker matterMarker = new MatterMarker(marker);
            try {
                for (int cx = chunkX - 4; cx <= chunkX + 4; cx++) {
                    for (int cz = chunkZ - 4; cz <= chunkZ + 4; cz++) {
                        for (IrisPosition position : engine.getMantle().findMarkers(cx, cz, matterMarker)) {
                            hits.add(new int[]{position.getX(), position.getY(), position.getZ()});
                        }
                    }
                }
            } catch (Throwable e) {
                LOGGER.error("Iris marker scan failed for {}", marker, e);
                server.execute(() -> fail(source, "Marker scan failed: " + e.getClass().getSimpleName()));
                return;
            }
            server.execute(() -> {
                for (int[] hit : hits) {
                    level.sendParticles(player, MARKER_DUST, true, true,
                            hit[0] + 0.5D, hit[1] + 1.0D, hit[2] + 0.5D,
                            3, 0.2D, 0.2D, 0.2D, 0.0D);
                }
                ok(source, "Found " + hits.size() + " nearby marker(s) (" + marker + ")");
            });
        }, "Iris Marker Scan");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int gotoBiome(CommandSourceStack source, String key) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        IrisBiome biome = engine.getData().getBiomeLoader().load(key.trim());
        if (biome == null) {
            fail(source, "Unknown biome: " + key);
            return 0;
        }
        locate(source, level, engine, player, Locator.surfaceBiome(biome.getLoadKey()), "biome " + biome.getLoadKey());
        return 1;
    }

    private static int gotoRegion(CommandSourceStack source, String key) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        IrisRegion region = engine.getData().getRegionLoader().load(key.trim());
        if (region == null) {
            fail(source, "Unknown region: " + key);
            return 0;
        }
        if (!engine.getDimension().getRegions().contains(region.getLoadKey())) {
            fail(source, region.getLoadKey() + " is not defined in the dimension!");
            return 0;
        }
        locate(source, level, engine, player, Locator.region(region.getLoadKey()), "region " + region.getLoadKey());
        return 1;
    }

    private static int gotoObject(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        String key = keyRaw.trim();
        if (!engine.hasObjectPlacement(key)) {
            fail(source, key + " is not configured in any region/biome object placements ("
                    + engine.getData().getObjectLoader().getPossibleKeys().length + " object keys loaded).");
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players. (object key '" + key + "' resolved against "
                    + engine.getData().getObjectLoader().getPossibleKeys().length + " loaded object keys)");
            return 0;
        }
        locate(source, level, engine, player, Locator.object(key), "object " + key);
        return 1;
    }

    private static int gotoStructure(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        String key = keyRaw.trim();
        if (key.isEmpty()) {
            fail(source, "Name an Iris or native structure to locate.");
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players.");
            return 0;
        }
        Optional<NativeStructureTarget> resolved = resolveNativeStructure(source, level, engine, key);
        if (resolved.isEmpty()) {
            if (IrisStructureLocator.isPlaced(engine, key)) {
                locateIrisStructure(source, level, engine, player, key);
                return 1;
            }
            fail(source, "Unknown structure '" + key + "'. Use tab completion to choose an Iris placement or a registered native/datapack structure.");
            return 0;
        }
        NativeStructureTarget target = resolved.get();
        IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(engine, target.key(), false);
        if (!decision.generate()
                && decision.status() != NativeStructureGenerationStatus.REPLACED_BY_IRIS) {
            fail(source, NativeStructureGenerationPolicy.generationStatusMessage(
                    target.key(), decision.status()));
            return 0;
        }
        if (decision.status() == NativeStructureGenerationStatus.REPLACED_BY_IRIS) {
            locateIrisStructure(source, level, engine, player, target.key());
            return 1;
        }
        if (target.availability() != NativeStructureAvailability.AVAILABLE) {
            fail(source, nativeUnavailableMessage(target.key(), target.availability()));
            return 0;
        }
        ok(source, "Searching for native structure " + target.key() + " within " + NATIVE_STRUCTURE_LOCATE_RADIUS + " chunks...");
        runNativeStructureLocate(source, level, player, target);
        return 1;
    }

    private static void locateIrisStructure(CommandSourceStack source, ServerLevel level, Engine engine,
                                            ServerPlayer player, String key) {
        MinecraftServer server = source.getServer();
        int blockX = player.blockPosition().getX();
        int blockZ = player.blockPosition().getZ();
        ok(source, "Searching for Iris-placed structure " + key + "...");
        Thread thread = new Thread(() -> {
            try {
                IrisStructureLocator.LocateResult result =
                        IrisStructureLocator.locate(engine, key, blockX, blockZ, 1024);
                if (result.status() == IrisStructureLocator.LocateStatus.SEARCH_LIMIT_REACHED) {
                    server.execute(() -> fail(source, "Unable to locate Iris-placed structure " + key
                            + ": the density search safety limit was reached before the full 1024-chunk radius was searched."));
                    return;
                }
                if (!result.found()) {
                    server.execute(() -> fail(source, "Could not find Iris-placed structure " + key + " within 1024 chunks."));
                    return;
                }
                int targetX = result.originX();
                int targetY = result.baseY() + 2;
                int targetZ = result.originZ();
                server.execute(() -> teleportToStructure(source, level, player, targetX, targetY, targetZ,
                        "Iris-placed structure " + key));
            } catch (Throwable e) {
                LOGGER.error("Iris structure locate failed for {}", key, e);
                server.execute(() -> fail(source, "Search failed: " + e.getClass().getSimpleName()));
            }
        }, "Iris Structure Locator");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runNativeStructureLocate(CommandSourceStack source, ServerLevel level,
                                                 ServerPlayer player, NativeStructureTarget target) {
        MinecraftServer server = source.getServer();
        Runnable locateTask = () -> locateNativeStructure(source, level, player, target);
        if (Thread.currentThread() == server.getRunningThread()) {
            locateTask.run();
            return;
        }
        server.execute(locateTask);
    }

    private static void locateNativeStructure(CommandSourceStack source, ServerLevel level,
                                              ServerPlayer player, NativeStructureTarget target) {
        try {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            Pair<BlockPos, Holder<Structure>> found = generator.findNearestMapStructure(
                    level,
                    HolderSet.direct(target.holder()),
                    player.blockPosition(),
                    NATIVE_STRUCTURE_LOCATE_RADIUS,
                    false);
            if (found == null) {
                fail(source, "Could not find native structure " + target.key() + " within "
                        + NATIVE_STRUCTURE_LOCATE_RADIUS + " chunks.");
                return;
            }
            BlockPos position = found.getFirst();
            int targetX = position.getX();
            int targetZ = position.getZ();
            level.getChunk(targetX >> 4, targetZ >> 4);
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ) + 1;
            int targetY = Math.max(level.getMinY() + 1, Math.min(level.getMaxY() - 1, surfaceY));
            teleportToStructure(source, level, player, targetX, targetY, targetZ,
                    "native structure " + target.key());
        } catch (Throwable e) {
            LOGGER.error("Native structure locate failed for {}", target.key(), e);
            fail(source, "Search for native structure " + target.key() + " failed: " + e.getClass().getSimpleName());
        }
    }

    private static void teleportToStructure(CommandSourceStack source, ServerLevel level, ServerPlayer player,
                                            int targetX, int targetY, int targetZ, String label) {
        if (player.hasDisconnected() || player.isRemoved()) {
            fail(source, "The player disconnected before the structure search completed.");
            return;
        }
        if (player.level() != level) {
            fail(source, "You changed dimensions before the structure search completed; run the command again.");
            return;
        }
        level.getChunk(targetX >> 4, targetZ >> 4);
        int clampedY = Math.max(level.getMinY() + 1, Math.min(level.getMaxY() - 1, targetY));
        boolean teleported = player.teleportTo(level, targetX + 0.5D, clampedY, targetZ + 0.5D,
                Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        if (!teleported) {
            fail(source, "Found " + label + " at " + targetX + " " + clampedY + " " + targetZ + ", but teleportation failed.");
            return;
        }
        ok(source, "Teleported to " + label + " at " + targetX + " " + clampedY + " " + targetZ);
    }

    static int verifyStructures(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        String key = keyRaw == null ? "" : keyRaw.trim();
        if (!key.isEmpty()) {
            return verifyStructure(source, level, engine, key);
        }
        Registry<Structure> registry = source.getServer().registryAccess().lookupOrThrow(Registries.STRUCTURE);
        int available = 0;
        int disabled = 0;
        int suppressed = 0;
        int unreachableBiomes = 0;
        int unsupported = 0;
        for (Identifier identifier : registry.keySet()) {
            Optional<Holder.Reference<Structure>> holder = registry.get(identifier);
            if (holder.isEmpty()) {
                continue;
            }
            NativeStructureAvailability availability = nativeAvailability(source, level, engine,
                    identifier.toString(), holder.get());
            switch (availability) {
                case AVAILABLE -> available++;
                case WORLD_DISABLED, FILTERED -> disabled++;
                case IRIS_SUPPRESSED -> suppressed++;
                case BIOME_UNREACHABLE -> unreachableBiomes++;
                case NO_PLACEMENT -> unsupported++;
            }
        }
        int irisPlaced = IrisStructureLocator.placedKeys(engine).size();
        ok(source, "Structure reachability: " + available + " native generation-eligible, " + irisPlaced
                + " Iris-placed, " + disabled + " native disabled, " + suppressed
                + " native replaced by Iris placements, " + unreachableBiomes
                + " native excluded by this pack's biomes, and " + unsupported
                + " registered native structures unsupported in this dimension. Use /iris structure verify <key> for one structure.");
        return 1;
    }

    private static int verifyStructure(CommandSourceStack source, ServerLevel level, Engine engine, String key) {
        Optional<NativeStructureTarget> target = resolveNativeStructure(source, level, engine, key);
        if (target.isEmpty()) {
            if (IrisStructureLocator.isPlaced(engine, key)) {
                ok(source, "Structure " + key + " is Iris-placed and locatable with /iris goto structure " + key + ".");
                return 1;
            }
            fail(source, "Unknown structure '" + key + "'. It is neither Iris-placed nor registered by vanilla or a datapack.");
            return 0;
        }
        NativeStructureTarget resolved = target.get();
        if (resolved.availability() == NativeStructureAvailability.IRIS_SUPPRESSED) {
            ok(source, "Structure " + resolved.key()
                    + " is explicitly replaced by an Iris placement and locatable with /iris goto structure "
                    + resolved.key() + ".");
            return 1;
        }
        if (resolved.availability() != NativeStructureAvailability.AVAILABLE) {
            fail(source, nativeUnavailableMessage(resolved.key(), resolved.availability()));
            return 0;
        }
        ok(source, "Native structure " + resolved.key() + " is enabled, supported by this dimension's generator state, and locatable with /iris goto structure " + resolved.key() + ".");
        return 1;
    }

    private static Optional<NativeStructureTarget> resolveNativeStructure(CommandSourceStack source,
                                                                           ServerLevel level,
                                                                           Engine engine,
                                                                           String keyRaw) {
        Identifier identifier = Identifier.tryParse(keyRaw);
        if (identifier == null) {
            return Optional.empty();
        }
        Registry<Structure> registry = source.getServer().registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Optional<Holder.Reference<Structure>> holder = registry.get(identifier);
        if (holder.isEmpty()) {
            return Optional.empty();
        }
        String key = identifier.toString();
        NativeStructureAvailability availability = nativeAvailability(source, level, engine, key, holder.get());
        return Optional.of(new NativeStructureTarget(key, holder.get(), availability));
    }

    private static NativeStructureAvailability nativeAvailability(CommandSourceStack source, ServerLevel level,
                                                                   Engine engine, String key,
                                                                   Holder.Reference<Structure> holder) {
        boolean worldEnabled = source.getServer().getWorldGenSettings().options().generateStructures();
        IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(engine, key, false);
        boolean selected = decision.status() != NativeStructureGenerationStatus.DISABLED_BY_PACK;
        boolean suppressed = decision.status() == NativeStructureGenerationStatus.REPLACED_BY_IRIS;
        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        boolean biomeReachable = chunkGenerator instanceof IrisModdedChunkGenerator irisGenerator
                && irisGenerator.isNativeStructureReachable(holder);
        boolean hasPlacement = false;
        if (worldEnabled && selected && !suppressed && biomeReachable) {
            hasPlacement = !level.getChunkSource().getGeneratorState().getPlacementsForStructure(holder).isEmpty();
        }
        return classifyNativeAvailability(worldEnabled, selected, suppressed, biomeReachable, hasPlacement);
    }

    static NativeStructureAvailability classifyNativeAvailability(boolean worldEnabled, boolean selected,
                                                                   boolean suppressed, boolean biomeReachable,
                                                                   boolean hasPlacement) {
        if (!worldEnabled) {
            return NativeStructureAvailability.WORLD_DISABLED;
        }
        if (!selected) {
            return NativeStructureAvailability.FILTERED;
        }
        if (suppressed) {
            return NativeStructureAvailability.IRIS_SUPPRESSED;
        }
        if (!biomeReachable) {
            return NativeStructureAvailability.BIOME_UNREACHABLE;
        }
        if (!hasPlacement) {
            return NativeStructureAvailability.NO_PLACEMENT;
        }
        return NativeStructureAvailability.AVAILABLE;
    }

    private static String nativeUnavailableMessage(String key, NativeStructureAvailability availability) {
        return switch (availability) {
            case WORLD_DISABLED -> "Native structure generation is disabled for this world, so " + key + " cannot generate or be located.";
            case FILTERED -> NativeStructureGenerationPolicy.generationStatusMessage(
                    key, NativeStructureGenerationStatus.DISABLED_BY_PACK);
            case IRIS_SUPPRESSED -> NativeStructureGenerationPolicy.generationStatusMessage(
                    key, NativeStructureGenerationStatus.REPLACED_BY_IRIS);
            case BIOME_UNREACHABLE -> "Native structure " + key + " cannot generate because none of its required biomes are produced by this Iris pack.";
            case NO_PLACEMENT -> "Native structure " + key + " is registered, but its structure set has no placement supported by this dimension's generator state.";
            case AVAILABLE -> "Native structure " + key + " is available.";
        };
    }

    private static int gotoPoi(CommandSourceStack source, String typeRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        String type = typeRaw.trim();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "This command can only be used by players. (POI type '" + type + "' accepted)");
            return 0;
        }
        locate(source, level, engine, player, Locator.poi(type), "POI " + type);
        return 1;
    }

    private static void locate(CommandSourceStack source, ServerLevel level, Engine engine, ServerPlayer player, Locator<?> locator, String label) {
        MinecraftServer server = source.getServer();
        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        ok(source, "Searching for " + label + "...");
        Thread thread = new Thread(() -> {
            try {
                Position2 at = locator.find(engine, new Position2(chunkX, chunkZ), LOCATE_TIMEOUT_MS, (Integer checks) -> {
                }).get();
                if (at == null) {
                    server.execute(() -> fail(source, "Could not find " + label + " within the search timeout."));
                    return;
                }
                int blockX = (at.getX() << 4) + 8;
                int blockZ = (at.getZ() << 4) + 8;
                int blockY = engine.getMinHeight() + engine.getHeight(blockX, blockZ, false) + 2;
                server.execute(() -> {
                    player.teleportTo(level, blockX + 0.5D, blockY, blockZ + 0.5D, Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
                    ok(source, "Teleported to " + label + " at " + blockX + " " + blockY + " " + blockZ);
                });
            } catch (WrongEngineBroException e) {
                server.execute(() -> fail(source, "The engine for this world has been closed; rejoin the dimension and try again."));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                LOGGER.error("Iris locate failed for {}", label, e);
                server.execute(() -> fail(source, "Search failed: " + e.getCause()));
            }
        }, "Iris Locator");
        thread.setDaemon(true);
        thread.start();
    }

    private static int seed(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        ok(source, "World seed: " + level.getSeed());
        ok(source, "Engine seed: " + engine.getSeedManager().getSeed() + " (mixed: " + engine.getSeedManager().getFullMixedSeed() + ")");
        return 1;
    }

    private static int goldenhash(CommandSourceStack source, int radius, int threads, ModdedGoldenHash.Mode mode) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        ModdedGoldenHash.start(source, level, engine, radius, threads, mode);
        return 1;
    }

    private static int download(CommandSourceStack source, String pack, String branch) {
        MinecraftServer server = source.getServer();
        boolean defaultOverworld = PackDownloader.isDefaultOverworld(pack);
        String downloadSource = defaultOverworld ? "beta release" : "branch " + branch;
        ok(source, "Downloading IrisDimensions/" + pack + " (" + downloadSource + ")...");
        Thread thread = new Thread(() -> {
            boolean installed = ModdedPackInstaller.install(ModdedEngineBootstrap.loader().configDir(), pack, branch,
                    (String message) -> server.execute(() -> ok(source, message)));
            if (installed) {
                server.execute(() -> ok(source, "Pack '" + pack + "' installed. Its exact dimension types and custom biomes join the forced Iris datapack on the next server restart; restart before creating worlds from this pack."));
            } else {
                server.execute(() -> fail(source, "Pack download failed for " + pack + " (" + downloadSource + "; see console)."));
            }
        }, "Iris Pack Download");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int metrics(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, "This dimension is not generated by Iris.");
            return 0;
        }
        ok(source, "Generated: " + engine.getGenerated() + " chunk(s), " + String.format("%.1f", engine.getGeneratedPerSecond()) + "/s");
        KMap<String, Double> pulled = engine.getMetrics().pull();
        Map<String, Double> sorted = new TreeMap<>(pulled);
        for (Map.Entry<String, Double> entry : sorted.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0D) {
                continue;
            }
            ok(source, "  " + entry.getKey() + ": " + String.format("%.2f", entry.getValue()) + "ms");
        }
        return 1;
    }

    static Engine engineFor(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (generator instanceof IrisModdedChunkGenerator irisGenerator) {
            try {
                return irisGenerator.commandEngine();
            } catch (Throwable e) {
                LOGGER.error("Iris engine lookup failed for {}", level.dimension().identifier(), e);
                return null;
            }
        }
        return null;
    }

    private static int engineCount(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                count++;
            }
        }
        return count;
    }

    private static CompletableFuture<Suggestions> suggestBiomeKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getBiomeLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRegionKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getDimension().getRegions(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestObjectKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getObjectLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }

    static CompletableFuture<Suggestions> suggestStructureKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = engineFor(context.getSource().getLevel());
            Collection<String> irisKeys = engine == null ? List.of() : IrisStructureLocator.placedKeys(engine);
            Registry<Structure> registry = context.getSource().getServer().registryAccess().lookupOrThrow(Registries.STRUCTURE);
            List<String> nativeKeys = new ArrayList<>(registry.keySet().size());
            for (Identifier identifier : registry.keySet()) {
                nativeKeys.add(identifier.toString());
            }
            return SharedSuggestionProvider.suggest(combineStructureKeys(irisKeys, nativeKeys), builder);
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }

    static List<String> combineStructureKeys(Collection<String> irisKeys, Collection<String> nativeKeys) {
        Set<String> combined = new TreeSet<>();
        combined.addAll(irisKeys);
        combined.addAll(nativeKeys);
        return List.copyOf(combined);
    }

    private static CompletableFuture<Suggestions> suggestPackNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        List<String> names = new ArrayList<>();
        names.add("overworld");
        try {
            File packs = ModdedEngineBootstrap.loader().configDir().resolve("irisworldgen").resolve("packs").toFile();
            File[] children = packs.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && !names.contains(child.getName())) {
                        names.add(child.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static CompletableFuture<Suggestions> suggestDimensionNames(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        List<String> names = new ArrayList<>();
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator) {
                names.add(level.dimension().identifier().toString());
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    static void ok(CommandSourceStack source, String message) {
        ModdedCommandFeedback.ok(source, message);
    }

    static void ok(CommandSourceStack source, Component component) {
        ModdedCommandFeedback.ok(source, component);
    }

    static void fail(CommandSourceStack source, String message) {
        ModdedCommandFeedback.fail(source, message);
    }

    enum NativeStructureAvailability {
        AVAILABLE,
        WORLD_DISABLED,
        FILTERED,
        IRIS_SUPPRESSED,
        BIOME_UNREACHABLE,
        NO_PLACEMENT
    }

    private record NativeStructureTarget(String key, Holder.Reference<Structure> holder,
                                         NativeStructureAvailability availability) {
    }
}
