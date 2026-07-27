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
import art.arcane.iris.core.localization.IrisMessages;
import art.arcane.iris.core.gui.GuiHost;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.core.pack.PackDownloader;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.framework.GenerationSessionLease;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.Locator;
import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.framework.WrongEngineBroException;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.modded.IrisModdedChunkGenerator;
import art.arcane.iris.modded.ModdedDimensionManager;
import art.arcane.iris.modded.ModdedEngineBootstrap;
import art.arcane.iris.modded.ModdedLoader;
import art.arcane.iris.modded.ModdedPackInstaller;
import art.arcane.iris.modded.ModdedScheduler;
import art.arcane.iris.modded.ModdedWorldgenIds;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.math.Position2;
import com.mojang.datafixers.util.Pair;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
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
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.ModdedCommandMessages;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
public final class IrisModdedCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final long LOCATE_TIMEOUT_MS = 120000L;
    private static final int NATIVE_STRUCTURE_LOCATE_RADIUS = 100;
    private static final ConcurrentHashMap<UUID, CompletableFuture<Position2>> ACTIVE_LOCATE_REQUESTS = new ConcurrentHashMap<>();

    private static final SuggestionProvider<CommandSourceStack> BIOME_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestBiomeKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> REGION_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestRegionKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> OBJECT_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestObjectKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> STRUCTURE_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestStructureKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> POI_TYPES = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> SharedSuggestionProvider.suggest(List.of("buried_treasure"), builder);
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

        root.then(ModdedWhatCommands.tree());

        root.then(teleportTree("teleport"));
        root.then(teleportTree("tp"));

        root.then(Commands.literal("evacuate").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> evacuate(context.getSource(), null))
                .then(Commands.argument("dimension", DimensionArgument.dimension()).suggests(DIMENSION_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) -> evacuate(context.getSource(), DimensionArgument.getDimension(context, "dimension")))));

        root.then(Commands.literal("debug").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> debug(context.getSource())));

        root.then(Commands.literal("reload").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> reload(context.getSource())));
        root.then(Commands.literal("height").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> height(context.getSource())));
        root.then(Commands.literal("worlds").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> info(context.getSource(), null)));
        root.then(Commands.literal("accesslist").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> info(context.getSource(), null)));

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
        root.then(Commands.literal("dust").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedObjectCommands.giveDust(context.getSource())));
        root.then(Commands.literal("d").requires(GATE)
                .executes((CommandContext<CommandSourceStack> context) -> ModdedObjectCommands.giveDust(context.getSource())));
        root.then(ModdedObjectCommands.tree("object"));
        root.then(ModdedObjectCommands.tree("o"));
        root.then(editTree());

        root.then(createTree("create"));
        root.then(createTree("c"));

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

    private static LiteralArgumentBuilder<CommandSourceStack> createTree(String name) {
        return Commands.literal(name).requires(GATE)
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes((CommandContext<CommandSourceStack> context) ->
                                ModdedWorldCommands.createWorld(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        "overworld",
                                        1337L))
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

    private static LiteralArgumentBuilder<CommandSourceStack> teleportTree(String name) {
        return Commands.literal(name).requires(GATE)
                .then(Commands.argument("dimension", DimensionArgument.dimension()).suggests(DIMENSION_NAMES)
                        .executes((CommandContext<CommandSourceStack> context) ->
                                tp(context.getSource(), DimensionArgument.getDimension(context, "dimension"), null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes((CommandContext<CommandSourceStack> context) ->
                                        tp(context.getSource(), DimensionArgument.getDimension(context, "dimension"),
                                                EntityArgument.getPlayer(context, "player")))));
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
                        .executes((CommandContext<CommandSourceStack> context) ->
                                download(context.getSource(),
                                        StringArgumentType.getString(context, "pack"), "stable", false))
                        .then(Commands.literal("force")
                                .executes((CommandContext<CommandSourceStack> context) ->
                                        download(context.getSource(),
                                                StringArgumentType.getString(context, "pack"), "stable", true)))
                        .then(Commands.argument("overwrite", BoolArgumentType.bool())
                                .executes((CommandContext<CommandSourceStack> context) ->
                                        download(context.getSource(),
                                                StringArgumentType.getString(context, "pack"), "stable",
                                                BoolArgumentType.getBool(context, "overwrite"))))
                        .then(Commands.argument("branch", StringArgumentType.word())
                                .executes((CommandContext<CommandSourceStack> context) ->
                                        download(context.getSource(),
                                                StringArgumentType.getString(context, "pack"),
                                                StringArgumentType.getString(context, "branch"), false))
                                .then(Commands.literal("force")
                                        .executes((CommandContext<CommandSourceStack> context) ->
                                                download(context.getSource(),
                                                        StringArgumentType.getString(context, "pack"),
                                                        StringArgumentType.getString(context, "branch"), true)))
                                .then(Commands.argument("overwrite", BoolArgumentType.bool())
                                        .executes((CommandContext<CommandSourceStack> context) ->
                                                download(context.getSource(),
                                                        StringArgumentType.getString(context, "pack"),
                                                        StringArgumentType.getString(context, "branch"),
                                                        BoolArgumentType.getBool(context, "overwrite"))))));
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
                .then(Commands.literal("b")
                        .executes((CommandContext<CommandSourceStack> context) -> editBiome(context.getSource(), null))
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(BIOME_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> editBiome(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("region")
                        .executes((CommandContext<CommandSourceStack> context) -> editRegion(context.getSource(), null))
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(REGION_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> editRegion(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("r")
                        .executes((CommandContext<CommandSourceStack> context) -> editRegion(context.getSource(), null))
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(REGION_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> editRegion(context.getSource(), StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("dimension")
                        .executes((CommandContext<CommandSourceStack> context) -> editDimension(context.getSource())))
                .then(Commands.literal("d")
                        .executes((CommandContext<CommandSourceStack> context) -> editDimension(context.getSource())));
    }

    private static int editBiome(CommandSourceStack source, String key) {
        Engine engine = engineFor(source.getLevel());
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS));
            return 0;
        }
        IrisBiome biome;
        if (key == null || key.isBlank()) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_CONSOLE_MUST_NAME_BIOME_IRIS_EDIT_BIOME_KEY));
                return 0;
            }
            BlockPos pos = player.blockPosition();
            try {
                biome = engine.getBiome(pos.getX(), pos.getY() - engine.getMinHeight(), pos.getZ());
            } catch (Throwable e) {
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_BIOME_LOOKUP_FAILED, MessageArgument.untrusted("value", e.getClass().getSimpleName())));
                return 0;
            }
        } else {
            biome = engine.getData().getBiomeLoader().load(key.trim());
            if (biome == null) {
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_UNKNOWN_BIOME, MessageArgument.untrusted("key", key)));
                return 0;
            }
        }
        return openJson(source, biome);
    }

    private static int editRegion(CommandSourceStack source, String key) {
        Engine engine = engineFor(source.getLevel());
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_2));
            return 0;
        }
        IrisRegion region;
        if (key == null || key.isBlank()) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_CONSOLE_MUST_NAME_REGION_IRIS_EDIT_REGION_KEY));
                return 0;
            }
            BlockPos pos = player.blockPosition();
            try {
                region = engine.getRegion(pos.getX(), pos.getZ());
            } catch (Throwable e) {
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_REGION_LOOKUP_FAILED, MessageArgument.untrusted("value", e.getClass().getSimpleName())));
                return 0;
            }
        } else {
            region = engine.getData().getRegionLoader().load(key.trim());
            if (region == null) {
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_UNKNOWN_REGION, MessageArgument.untrusted("key", key)));
                return 0;
            }
        }
        return openJson(source, region);
    }

    private static int editDimension(CommandSourceStack source) {
        Engine engine = engineFor(source.getLevel());
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_3));
            return 0;
        }
        return openJson(source, engine.getDimension());
    }

    private static int openJson(CommandSourceStack source, IrisRegistrant registrant) {
        if (!GuiHost.isAvailable() || !Desktop.isDesktopSupported()) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_CANNOT_OPEN_FILES_HERE, MessageArgument.untrusted("value", ModdedGuiHost.guiUnavailableReason())));
            return 0;
        }
        if (registrant == null || registrant.getLoadFile() == null || !registrant.getLoadFile().isFile()) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_CANNOT_FIND_FILE_PERHAPS_IT_WAS_NOT_LOADED_DIRECTLY_FROM));
            return 0;
        }
        File file = registrant.getLoadFile();
        try {
            Desktop.getDesktop().open(file);
        } catch (Throwable e) {
            LOGGER.error("Iris edit failed to open {}", file, e);
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_COULD_NOT_OPEN, MessageArgument.untrusted("value", file.getName()), MessageArgument.untrusted("value2", e.getClass().getSimpleName())));
            return 0;
        }
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_OPENING_YOUR_EDITOR, MessageArgument.untrusted("value", registrant.getTypeName()), MessageArgument.untrusted("value2", file.getName())));
        return 1;
    }

    private static int tp(CommandSourceStack source, ServerLevel level, ServerPlayer target) {
        ServerPlayer player = target != null ? target : source.getPlayer();
        if (player == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_CONSOLE_MUST_NAME_PLAYER_IRIS_TP_DIMENSION_PLAYER));
            return 0;
        }
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator)) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_IS_NOT_GENERATED_BY_IRIS, MessageArgument.untrusted("value", level.dimension().identifier())));
            return 0;
        }
        String dimensionId = level.dimension().identifier().toString();
        if (!ModdedDimensionManager.teleport(player, source.getServer(), dimensionId, 8.5D, Double.MIN_VALUE, 8.5D)) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_TELEPORT_FAILED_DIMENSION_IS_NOT_LOADED, MessageArgument.untrusted("dimensionId", dimensionId)));
            return 0;
        }
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_TELEPORTING, MessageArgument.untrusted("value", player.getScoreboardName()), MessageArgument.untrusted("dimensionId", dimensionId)));
        return 1;
    }

    private static int evacuate(CommandSourceStack source, ServerLevel target) {
        MinecraftServer server = source.getServer();
        ServerLevel level = target != null ? target : source.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator)) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_IS_NOT_GENERATED_BY_IRIS_2, MessageArgument.untrusted("value", level.dimension().identifier())));
            return 0;
        }
        ServerLevel fallback = server.overworld();
        if (fallback == level) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_CANNOT_EVACUATE_PRIMARY_WORLD_THERE_IS_NOWHERE_SEND_PLAYERS));
            return 0;
        }
        int count = ModdedDimensionManager.evacuate(server, level);
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_EVACUATED_PLAYER_S_FROM, MessageArgument.untrusted("count", count), MessageArgument.untrusted("value", level.dimension().identifier()), MessageArgument.untrusted("value2", fallback.dimension().identifier())));
        return 1;
    }

    private static int debug(CommandSourceStack source) {
        boolean to = !IrisSettings.get().getGeneral().isDebug();
        IrisSettings.get().getGeneral().setDebug(to);
        IrisSettings.get().forceSave();
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_SET_DEBUG, MessageArgument.untrusted("to", to)));
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        if (IrisSettings.settings != null) {
            IrisSettings.invalidate();
        }
        IrisSettings.get();
        boolean localeLoaded = IrisLanguage.reload();
        if (localeLoaded) {
            ok(source, IrisLanguage.plain(
                    IrisMessages.COMMAND_RELOAD_SUCCESS,
                    MessageArgument.trusted("locale", IrisLanguage.activeLocale())
            ));
            return 1;
        }
        fail(source, IrisLanguage.plain(
                IrisMessages.COMMAND_RELOAD_FAILED,
                MessageArgument.untrusted("locale", IrisSettings.get().getGeneral().getLanguage()),
                MessageArgument.trusted("activeLocale", IrisLanguage.activeLocale())
        ));
        return 0;
    }

    private static int height(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        IrisModdedCommands.ok(source, IrisLanguage.plain(
                RuntimeUiMessages.WORLD_HEIGHT_RANGE,
                MessageArgument.trusted("minY", level.getMinY()),
                MessageArgument.trusted("maxY", level.getMaxY())));
        IrisModdedCommands.ok(source, IrisLanguage.plain(
                RuntimeUiMessages.WORLD_HEIGHT_TOTAL,
                MessageArgument.trusted("height", level.getHeight())));
        return 1;
    }

    private static int regen(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_COMMAND_CAN_ONLY_BE_USED_BY_PLAYERS));
            return 0;
        }
        ServerLevel level = source.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof IrisModdedChunkGenerator irisGenerator)) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_4));
            return 0;
        }
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_5));
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
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_IS_NOT_GENERATED_BY_IRIS_SEE_IRIS_INFO_LOADED_IRIS, MessageArgument.untrusted("value", level.dimension().identifier())));
            } else {
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_CURRENT_DIMENSION_IS_NOT_GENERATED_BY_IRIS_NAME_ONE_EXPLICITLY, MessageArgument.untrusted("value", level.dimension().identifier()), MessageArgument.untrusted("radius", radius)));
            }
            return 0;
        }
        boolean showGui = gui && ModdedGuiHost.isGuiLaunchable();
        if (!ModdedPregenJob.start(source.getServer(), level, engine, radius, centerX, centerZ, showGui, sync, !nocache)) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_PREGENERATION_TASK_IS_ALREADY_RUNNING_STOP_IT_FIRST_WITH_IRIS));
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
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_PREGEN_STARTED_BY_BLOCKS_FROM_PROGRESS_LOGS_CONSOLE_SEE_IRIS, MessageArgument.untrusted("value", level.dimension().identifier()), MessageArgument.untrusted("value2", (radius * 2)), MessageArgument.untrusted("value3", (radius * 2)), MessageArgument.untrusted("centerX", centerX), MessageArgument.untrusted("centerZ", centerZ), MessageArgument.untrusted("modeNote", modeNote), MessageArgument.untrusted("guiNote", guiNote)));
        return 1;
    }

    private static int pregenStop(CommandSourceStack source) {
        if (ModdedPregenJob.stop()) {
            ModdedPregenBossBar.clear();
            ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_STOPPING_PREGENERATION_FINISHING_UP_CURRENT_REGION));
            return 1;
        }
        fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_NO_ACTIVE_PREGENERATION_TASK_STOP));
        return 0;
    }

    private static int pregenPause(CommandSourceStack source) {
        Boolean paused = ModdedPregenJob.pauseResume();
        if (paused == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_NO_ACTIVE_PREGENERATION_TASK_PAUSE_RESUME));
            return 0;
        }
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_PREGENERATION_IS_NOW, MessageArgument.trusted("value", IrisLanguage.plain(paused.booleanValue() ? RuntimeUiMessages.STATUS_PAUSED_LOWER : RuntimeUiMessages.STATUS_RUNNING_LOWER))));
        return 1;
    }

    private static int pregenStatus(CommandSourceStack source) {
        Component status = ModdedPregenJob.statusComponent();
        if (status == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_NO_ACTIVE_PREGENERATION_TASK));
            return 0;
        }
        ok(source, status);
        return 1;
    }

    private static int version(CommandSourceStack source) {
        ModdedLoader loader = ModdedEngineBootstrap.loader();
        int engines = engineCount(source.getServer());
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_IRIS_BY_VOLMIT_SOFTWARE_ON_MINECRAFT_IRIS_DIMENSION_S, MessageArgument.untrusted("value", loader.modVersion()), MessageArgument.untrusted("value2", loader.platformName()), MessageArgument.untrusted("value3", loader.minecraftVersion()), MessageArgument.untrusted("engines", engines)));
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
            String irisIdentity = ModdedWorldgenIds.generatorIdentity(irisGenerator.dimensionKey());
            if (filter != null && !dimensionId.contains(filter)
                    && !irisIdentity.contains(filter)
                    && !irisGenerator.dimensionKey().contains(filter)) {
                continue;
            }
            Engine engine = irisGenerator.engineIfBound();
            if (engine == null) {
                lines.add(irisIdentity + ": pack=" + irisGenerator.dimensionKey()
                        + " world=" + dimensionId + " (engine not started yet)");
                continue;
            }
            lines.add(irisIdentity + ": pack=" + engine.getDimension().getLoadKey()
                    + " world=" + dimensionId
                    + " seed=" + level.getSeed()
                    + " height=" + engine.getMinHeight() + ".." + engine.getMaxHeight()
                    + " generated=" + engine.getGenerated()
                    + " data=" + engine.getData().getDataFolder().getAbsolutePath());
        }
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_LOADED_DIMENSIONS_IRIS, MessageArgument.untrusted("total", total), MessageArgument.untrusted("iris", iris)));
        if (lines.isEmpty()) {
            if (filter == null) {
                ok(source, IrisLanguage.plain(ModdedCommandMessages.MODDED_DATAPACK_COMMANDS_NO_IRIS_DIMENSIONS_ARE_LOADED));
            } else {
                ok(source, IrisLanguage.plain(RuntimeUiMessages.MODDED_NO_DIMENSION_MATCH, MessageArgument.untrusted("filter", filter)));
            }
            return 0;
        }
        for (String line : lines) {
            ok(source, line);
        }
        return 1;
    }

    private static int gotoBiome(CommandSourceStack source, String key) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_COMMAND_CAN_ONLY_BE_USED_BY_PLAYERS_3));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_8));
            return 0;
        }
        IrisBiome biome = engine.getData().getBiomeLoader().load(key.trim());
        if (biome == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_UNKNOWN_BIOME_2, MessageArgument.untrusted("key", key)));
            return 0;
        }
        locate(source, level, engine, player, Locator.surfaceBiome(biome.getLoadKey()), "biome " + biome.getLoadKey());
        return 1;
    }

    private static int gotoRegion(CommandSourceStack source, String key) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_COMMAND_CAN_ONLY_BE_USED_BY_PLAYERS_4));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_9));
            return 0;
        }
        IrisRegion region = engine.getData().getRegionLoader().load(key.trim());
        if (region == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_UNKNOWN_REGION_2, MessageArgument.untrusted("key", key)));
            return 0;
        }
        if (!engine.getDimension().getRegions().contains(region.getLoadKey())) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_IS_NOT_DEFINED_DIMENSION, MessageArgument.untrusted("value", region.getLoadKey())));
            return 0;
        }
        locate(source, level, engine, player, Locator.region(region.getLoadKey()), "region " + region.getLoadKey());
        return 1;
    }

    private static int gotoObject(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_10));
            return 0;
        }
        String key = keyRaw.trim();
        if (!engine.hasObjectPlacement(key)) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_IS_NOT_CONFIGURED_ANY_REGION_BIOME_OBJECT_PLACEMENTS_OBJECT_KEYS, MessageArgument.untrusted("key", key), MessageArgument.untrusted("value", engine.getData().getObjectLoader().getPossibleKeys().length)));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_COMMAND_CAN_ONLY_BE_USED_BY_PLAYERS_OBJECT_KEY, MessageArgument.untrusted("key", key), MessageArgument.untrusted("value", engine.getData().getObjectLoader().getPossibleKeys().length)));
            return 0;
        }
        locate(source, level, engine, player, Locator.object(key), "object " + key);
        return 1;
    }

    private static int gotoStructure(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_11));
            return 0;
        }
        String key = keyRaw.trim();
        if (key.isEmpty()) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_NAME_IRIS_NATIVE_STRUCTURE_LOCATE));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_COMMAND_CAN_ONLY_BE_USED_BY_PLAYERS_5));
            return 0;
        }
        Optional<NativeStructureTarget> resolved = resolveNativeStructure(source, level, engine, key);
        if (resolved.isEmpty()) {
            if (IrisStructureLocator.isPlaced(engine, key)) {
                locateIrisStructure(source, level, engine, player, key);
                return 1;
            }
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_UNKNOWN_STRUCTURE_USE_TAB_COMPLETION_CHOOSE_IRIS_PLACEMENT_REGISTERED_NATIVE, MessageArgument.untrusted("key", key)));
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
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_SEARCHING_NATIVE_STRUCTURE_WITHIN_CHUNKS, MessageArgument.untrusted("value", target.key()), MessageArgument.untrusted("NATIVESTRUCTURELOCATERADIUS", NATIVE_STRUCTURE_LOCATE_RADIUS)));
        runNativeStructureLocate(source, level, player, target);
        return 1;
    }

    private static void locateIrisStructure(CommandSourceStack source, ServerLevel level, Engine engine,
                                            ServerPlayer player, String key) {
        MinecraftServer server = source.getServer();
        int blockX = player.blockPosition().getX();
        int blockZ = player.blockPosition().getZ();
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_SEARCHING_IRIS_PLACED_STRUCTURE, MessageArgument.untrusted("key", key)));
        Thread thread = new Thread(() -> {
            try {
                IrisStructureLocator.LocateResult result =
                        IrisStructureLocator.locate(engine, key, blockX, blockZ, 1024);
                if (result.status() == IrisStructureLocator.LocateStatus.SEARCH_LIMIT_REACHED) {
                    server.execute(() -> fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_UNABLE_LOCATE_IRIS_PLACED_STRUCTURE_DENSITY_SEARCH_SAFETY_LIMIT_WAS, MessageArgument.untrusted("key", key))));
                    return;
                }
                if (!result.found()) {
                    server.execute(() -> fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_COULD_NOT_FIND_IRIS_PLACED_STRUCTURE_WITHIN_1024_CHUNKS, MessageArgument.untrusted("key", key))));
                    return;
                }
                int targetX = result.originX();
                int targetY = result.baseY() + 2;
                int targetZ = result.originZ();
                server.execute(() -> teleportToStructure(source, level, player, targetX, targetY, targetZ,
                        "Iris-placed structure " + key));
            } catch (Throwable e) {
                LOGGER.error("Iris structure locate failed for {}", key, e);
                server.execute(() -> fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_SEARCH_FAILED, MessageArgument.untrusted("value", e.getClass().getSimpleName()))));
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
                fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_COULD_NOT_FIND_NATIVE_STRUCTURE_WITHIN_CHUNKS, MessageArgument.untrusted("value", target.key()), MessageArgument.untrusted("NATIVESTRUCTURELOCATERADIUS", NATIVE_STRUCTURE_LOCATE_RADIUS)));
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
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_SEARCH_NATIVE_STRUCTURE_FAILED, MessageArgument.untrusted("value", target.key()), MessageArgument.untrusted("value2", e.getClass().getSimpleName())));
        }
    }

    private static void teleportToStructure(CommandSourceStack source, ServerLevel level, ServerPlayer player,
                                            int targetX, int targetY, int targetZ, String label) {
        if (player.hasDisconnected() || player.isRemoved()) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_PLAYER_DISCONNECTED_BEFORE_STRUCTURE_SEARCH_COMPLETED));
            return;
        }
        if (player.level() != level) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_YOU_CHANGED_DIMENSIONS_BEFORE_STRUCTURE_SEARCH_COMPLETED_RUN_COMMAND_AGAIN));
            return;
        }
        level.getChunk(targetX >> 4, targetZ >> 4);
        int clampedY = Math.max(level.getMinY() + 1, Math.min(level.getMaxY() - 1, targetY));
        boolean teleported = player.teleportTo(level, targetX + 0.5D, clampedY, targetZ + 0.5D,
                Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
        if (!teleported) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_FOUND_AT_BUT_TELEPORTATION_FAILED, MessageArgument.untrusted("label", label), MessageArgument.untrusted("targetX", targetX), MessageArgument.untrusted("clampedY", clampedY), MessageArgument.untrusted("targetZ", targetZ)));
            return;
        }
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_TELEPORTED_AT, MessageArgument.untrusted("label", label), MessageArgument.untrusted("targetX", targetX), MessageArgument.untrusted("clampedY", clampedY), MessageArgument.untrusted("targetZ", targetZ)));
    }

    static int verifyStructures(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_12));
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
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_STRUCTURE_REACHABILITY_NATIVE_GENERATION_ELIGIBLE_IRIS_PLACED_NATIVE_DISABLED_NATIVE, MessageArgument.untrusted("available", available), MessageArgument.untrusted("irisPlaced", irisPlaced), MessageArgument.untrusted("disabled", disabled), MessageArgument.untrusted("suppressed", suppressed), MessageArgument.untrusted("unreachableBiomes", unreachableBiomes), MessageArgument.untrusted("unsupported", unsupported)));
        return 1;
    }

    private static int verifyStructure(CommandSourceStack source, ServerLevel level, Engine engine, String key) {
        Optional<NativeStructureTarget> target = resolveNativeStructure(source, level, engine, key);
        if (target.isEmpty()) {
            if (IrisStructureLocator.isPlaced(engine, key)) {
                ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_STRUCTURE_IS_IRIS_PLACED_LOCATABLE_WITH_IRIS_GOTO_STRUCTURE, MessageArgument.untrusted("key", key), MessageArgument.untrusted("key2", key)));
                return 1;
            }
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_UNKNOWN_STRUCTURE_IT_IS_NEITHER_IRIS_PLACED_NOR_REGISTERED_BY, MessageArgument.untrusted("key", key)));
            return 0;
        }
        NativeStructureTarget resolved = target.get();
        if (resolved.availability() == NativeStructureAvailability.IRIS_SUPPRESSED) {
            ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_STRUCTURE_IS_EXPLICITLY_REPLACED_BY_IRIS_PLACEMENT_LOCATABLE_WITH_IRIS, MessageArgument.untrusted("value", resolved.key()), MessageArgument.untrusted("value2", resolved.key())));
            return 1;
        }
        if (resolved.availability() != NativeStructureAvailability.AVAILABLE) {
            fail(source, nativeUnavailableMessage(resolved.key(), resolved.availability()));
            return 0;
        }
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_NATIVE_STRUCTURE_IS_ENABLED_SUPPORTED_BY_THIS_DIMENSION_S_GENERATOR, MessageArgument.untrusted("value", resolved.key()), MessageArgument.untrusted("value2", resolved.key())));
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
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_13));
            return 0;
        }
        String type = typeRaw.trim();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_COMMAND_CAN_ONLY_BE_USED_BY_PLAYERS_POI_TYPE, MessageArgument.untrusted("type", type)));
            return 0;
        }
        locate(source, level, engine, player, Locator.poi(type), "POI " + type);
        return 1;
    }

    private static void locate(CommandSourceStack source, ServerLevel level, Engine engine, ServerPlayer player, Locator<?> locator, String label) {
        MinecraftServer server = source.getServer();
        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_SEARCHING, MessageArgument.untrusted("label", label)));
        CompletableFuture<Position2> search;
        try {
            search = locator.find(engine, new Position2(chunkX, chunkZ), LOCATE_TIMEOUT_MS, (Integer checks) -> {
            });
        } catch (WrongEngineBroException e) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_ENGINE_THIS_WORLD_HAS_BEEN_CLOSED_REJOIN_DIMENSION_TRY_AGAIN));
            return;
        }
        UUID playerId = player.getUUID();
        CompletableFuture<Position2> previous = ACTIVE_LOCATE_REQUESTS.put(playerId, search);
        if (previous != null && previous != search) {
            previous.cancel(true);
        }
        search.whenComplete((Position2 at, Throwable error) -> completeLocate(
                source, level, engine, player, label, server, playerId, search, at, error));
    }

    private static void completeLocate(CommandSourceStack source, ServerLevel level, Engine engine,
                                       ServerPlayer player, String label, MinecraftServer server, UUID playerId,
                                       CompletableFuture<Position2> search, Position2 at, Throwable error) {
        if (ACTIVE_LOCATE_REQUESTS.get(playerId) != search) {
            return;
        }
        Throwable failure = unwrapCompletionFailure(error);
        if (failure instanceof CancellationException) {
            ACTIVE_LOCATE_REQUESTS.remove(playerId, search);
            return;
        }
        if (failure != null) {
            LOGGER.error("Iris locate failed for {}", label, failure);
            server.execute(() -> {
                if (ACTIVE_LOCATE_REQUESTS.remove(playerId, search)) {
                    fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_SEARCH_FAILED_2, MessageArgument.untrusted("failure", failure)));
                }
            });
            return;
        }
        if (at == null) {
            server.execute(() -> {
                if (ACTIVE_LOCATE_REQUESTS.remove(playerId, search)) {
                    fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_COULD_NOT_FIND_WITHIN_SEARCH_TIMEOUT, MessageArgument.untrusted("label", label)));
                }
            });
            return;
        }
        server.execute(() -> {
            if (ACTIVE_LOCATE_REQUESTS.remove(playerId, search)) {
                teleportToLocateResult(source, level, engine, player, label, at);
            }
        });
    }

    private static void teleportToLocateResult(CommandSourceStack source, ServerLevel level, Engine engine,
                                                ServerPlayer player, String label, Position2 at) {
        int blockX = (at.getX() << 4) + 8;
        int blockZ = (at.getZ() << 4) + 8;
        try (GenerationSessionLease lease = engine.acquireGenerationLease("modded_locator_teleport");
            IrisContext.Scope ignored = IrisContext.open(engine, lease.sessionId(), null)) {
            int blockY = engine.getMinHeight() + engine.getHeight(blockX, blockZ, false) + 2;
            boolean teleported = player.teleportTo(
                    level,
                    blockX + 0.5D,
                    blockY,
                    blockZ + 0.5D,
                    Set.<Relative>of(),
                    player.getYRot(),
                    player.getXRot(),
                    false);
            if (!teleported) {
                fail(source, IrisLanguage.plain(
                        ModdedCommandMessages.IRIS_MODDED_COMMANDS_FOUND_AT_BUT_TELEPORTATION_FAILED,
                        MessageArgument.untrusted("label", label),
                        MessageArgument.trusted("targetX", blockX),
                        MessageArgument.trusted("clampedY", blockY),
                        MessageArgument.trusted("targetZ", blockZ)));
                return;
            }
            ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_TELEPORTED_AT_2, MessageArgument.untrusted("label", label), MessageArgument.untrusted("blockX", blockX), MessageArgument.untrusted("blockY", blockY), MessageArgument.untrusted("blockZ", blockZ)));
        } catch (GenerationSessionException e) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_ENGINE_CHANGED_WHILE_LOCATING_TRY_AGAIN, MessageArgument.untrusted("label", label)));
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable error) {
        Throwable failure = error;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static int seed(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_14));
            return 0;
        }
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_WORLD_SEED, MessageArgument.untrusted("value", level.getSeed())));
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_ENGINE_SEED_MIXED, MessageArgument.untrusted("value", engine.getSeedManager().getSeed()), MessageArgument.untrusted("value2", engine.getSeedManager().getFullMixedSeed())));
        return 1;
    }

    private static int goldenhash(CommandSourceStack source, int radius, int threads, ModdedGoldenHash.Mode mode) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_15));
            return 0;
        }
        ModdedGoldenHash.start(source, level, engine, radius, threads, mode);
        return 1;
    }

    private static int download(CommandSourceStack source, String pack,
                                String branch, boolean forceOverwrite) {
        boolean defaultOverworld = PackDownloader.isDefaultOverworld(pack);
        String baseDownloadSource = defaultOverworld ? "beta release" : "branch " + branch;
        String downloadSource = forceOverwrite
                ? baseDownloadSource + IrisLanguage.plain(RuntimeUiMessages.DOWNLOAD_OVERWRITE_SUFFIX)
                : baseDownloadSource;
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_DOWNLOADING_IRISDIMENSIONS, MessageArgument.untrusted("pack", pack), MessageArgument.untrusted("downloadSource", downloadSource)));
        ModdedScheduler scheduler = ModdedEngineBootstrap.schedulerOrNull();
        if (scheduler == null) {
            fail(source, IrisLanguage.plain(
                    ModdedCommandMessages.IRIS_MODDED_COMMANDS_PACK_DOWNLOAD_FAILED_SEE_CONSOLE,
                    MessageArgument.untrusted("pack", pack),
                    MessageArgument.untrusted("downloadSource", downloadSource)));
            return 0;
        }
        scheduler.async(() -> {
            boolean installed = ModdedPackInstaller.install(
                    ModdedEngineBootstrap.loader().configDir(), pack, branch, forceOverwrite,
                    (String message) -> scheduler.global(() -> ok(source, message)));
            if (installed) {
                scheduler.global(() -> ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_PACK_INSTALLED_ITS_EXACT_DIMENSION_TYPES_CUSTOM_BIOMES_JOIN_FORCED, MessageArgument.untrusted("pack", pack))));
            } else {
                scheduler.global(() -> fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_PACK_DOWNLOAD_FAILED_SEE_CONSOLE, MessageArgument.untrusted("pack", pack), MessageArgument.untrusted("downloadSource", downloadSource))));
            }
        });
        return 1;
    }

    private static int metrics(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Engine engine = engineFor(level);
        if (engine == null) {
            fail(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_THIS_DIMENSION_IS_NOT_GENERATED_BY_IRIS_16));
            return 0;
        }
        ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_GENERATED_CHUNK_S_S, MessageArgument.untrusted("value", engine.getGenerated()), MessageArgument.untrusted("value2", String.format("%.1f", engine.getGeneratedPerSecond()))));
        KMap<String, Double> pulled = engine.getMetrics().pull();
        Map<String, Double> sorted = new TreeMap<>(pulled);
        for (Map.Entry<String, Double> entry : sorted.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0D) {
                continue;
            }
            ok(source, IrisLanguage.plain(ModdedCommandMessages.IRIS_MODDED_COMMANDS_MS, MessageArgument.untrusted("value", entry.getKey()), MessageArgument.untrusted("value2", String.format("%.2f", entry.getValue()))));
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
        Set<String> names = new TreeSet<>();
        names.add("overworld");
        try {
            File packs = ModdedEngineBootstrap.loader().configDir().resolve("irisworldgen").resolve("packs").toFile();
            File[] children = packs.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!child.isDirectory()) {
                        continue;
                    }
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
