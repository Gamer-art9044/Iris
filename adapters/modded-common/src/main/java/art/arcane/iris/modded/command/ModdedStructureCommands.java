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
import art.arcane.iris.core.structure.StructureIndexService;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.PlacedStructurePiece;
import art.arcane.iris.engine.framework.StructureAssembler;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.math.RNG;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class ModdedStructureCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private static final SuggestionProvider<CommandSourceStack> IRIS_STRUCTURE_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> suggestIrisStructureKeys(context, builder);
    private static final SuggestionProvider<CommandSourceStack> ALL_STRUCTURE_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> IrisModdedCommands.suggestStructureKeys(context, builder);

    private ModdedStructureCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(GATE);

        root.executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name));

        root.then(Commands.literal("list")
                .executes((CommandContext<CommandSourceStack> context) -> list(context.getSource())));
        root.then(Commands.literal("ls")
                .executes((CommandContext<CommandSourceStack> context) -> list(context.getSource())));

        root.then(Commands.literal("info")
                .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(IRIS_STRUCTURE_KEYS)
                        .executes((CommandContext<CommandSourceStack> context) -> info(context.getSource(), StringArgumentType.getString(context, "key")))));

        root.then(Commands.literal("place")
                .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(IRIS_STRUCTURE_KEYS)
                        .executes((CommandContext<CommandSourceStack> context) -> place(context.getSource(), StringArgumentType.getString(context, "key")))));
        root.then(Commands.literal("p")
                .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(IRIS_STRUCTURE_KEYS)
                        .executes((CommandContext<CommandSourceStack> context) -> place(context.getSource(), StringArgumentType.getString(context, "key")))));

        root.then(message("import", "Structure import rebuilds vanilla & datapack structures as editable Iris resources through Bukkit/NMS template managers; run /iris structure import on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("import-all", "Structure import rebuilds vanilla & datapack structures as editable Iris resources through Bukkit/NMS template managers; run /iris structure import on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("reimport", "Structure import rebuilds vanilla & datapack structures as editable Iris resources through Bukkit/NMS template managers; run /iris structure import on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("imp", "Structure import rebuilds vanilla & datapack structures as editable Iris resources through Bukkit/NMS template managers; run /iris structure import on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("all", "Structure import rebuilds vanilla & datapack structures as editable Iris resources through Bukkit/NMS template managers; run /iris structure import on a Bukkit server against this pack, then copy the pack folder over."));
        root.then(message("capture", "Structure capture generates each structure in a throwaway Bukkit scratch world to read its blocks; it requires the Bukkit plugin (v26 NMS binding)."));
        root.then(message("cap", "Structure capture generates each structure in a throwaway Bukkit scratch world to read its blocks; it requires the Bukkit plugin (v26 NMS binding)."));
        root.then(verifyTree("verify"));
        root.then(verifyTree("locateall"));

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> verifyTree(String name) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> IrisModdedCommands.verifyStructures(context.getSource(), null))
                .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(ALL_STRUCTURE_KEYS)
                        .executes((CommandContext<CommandSourceStack> context) -> IrisModdedCommands.verifyStructures(
                                context.getSource(), StringArgumentType.getString(context, "key"))));
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

    private static IrisData dataFor(CommandSourceStack source) {
        Engine engine = IrisModdedCommands.engineFor(source.getLevel());
        if (engine == null) {
            IrisModdedCommands.fail(source, "This dimension is not generated by Iris. Run this from an Iris dimension so the pack can be resolved.");
            return null;
        }
        return engine.getData();
    }

    private static int list(CommandSourceStack source) {
        IrisData data = dataFor(source);
        if (data == null) {
            return 0;
        }
        File file = StructureIndexService.write(data);
        IrisModdedCommands.ok(source, "Wrote structure index: " + file.getPath());
        return 1;
    }

    private static int info(CommandSourceStack source, String keyRaw) {
        IrisData data = dataFor(source);
        if (data == null) {
            return 0;
        }
        String key = keyRaw.trim();
        IrisStructure structure = IrisData.loadAnyStructure(key, data);
        if (structure == null) {
            IrisModdedCommands.fail(source, "No iris structure '" + key + "' in this pack");
            return 0;
        }
        StructureAssembler assembler = new StructureAssembler(data, structure, 0, 64, 0);
        KList<PlacedStructurePiece> pieces = assembler.assemble(new RNG(1234));
        if (pieces == null || pieces.isEmpty()) {
            IrisModdedCommands.fail(source, "Structure '" + key + "' assembled 0 pieces (check startPool '" + structure.getStartPool() + "')");
            return 0;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacedStructurePiece piece : pieces) {
            minX = Math.min(minX, piece.getMinX());
            minZ = Math.min(minZ, piece.getMinZ());
            maxX = Math.max(maxX, piece.getMaxX());
            maxZ = Math.max(maxZ, piece.getMaxZ());
        }
        IrisModdedCommands.ok(source, "Structure '" + key + "': " + pieces.size() + " pieces, footprint "
                + (maxX - minX + 1) + "x" + (maxZ - minZ + 1) + " blocks (sample seed 1234)");
        return 1;
    }

    private static int place(CommandSourceStack source, String keyRaw) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players (the structure is assembled at your position).");
            return 0;
        }
        ServerLevel level = source.getLevel();
        Engine engine = IrisModdedCommands.engineFor(level);
        IrisData data = dataFor(source);
        if (data == null) {
            return 0;
        }
        String key = keyRaw.trim();
        IrisStructure structure = IrisData.loadAnyStructure(key, data);
        if (structure == null) {
            IrisModdedCommands.fail(source, "No iris structure '" + key + "' in this pack");
            return 0;
        }
        int originX = player.blockPosition().getX();
        int originY = player.blockPosition().getY();
        int originZ = player.blockPosition().getZ();
        StructureAssembler assembler = new StructureAssembler(data, structure, originX, originY, originZ);
        RNG rng = new RNG((long) originX * 341873128712L + originZ);
        KList<PlacedStructurePiece> pieces = assembler.assemble(rng);
        if (pieces == null || pieces.isEmpty()) {
            IrisModdedCommands.fail(source, "Structure '" + key + "' assembled 0 pieces");
            return 0;
        }
        ModdedObjectPlacer placer = new ModdedObjectPlacer(level, engine);
        UUID owner = player.getUUID();
        try {
            for (PlacedStructurePiece piece : pieces) {
                IrisObjectPlacement config = new IrisObjectPlacement();
                config.setMode(ObjectPlaceMode.STRUCTURE_PIECE);
                config.setRotation(piece.getRotation());
                config.getPlace().add(piece.getObject().getLoadKey());
                if (!structure.getEdit().isEmpty()) {
                    config.setEdit(structure.getEdit());
                }
                piece.getObject().place(piece.getX(), piece.getY(), piece.getZ(), placer, config, rng, null, null, data);
            }
        } catch (Throwable e) {
            LOGGER.error("Iris structure place failed for {}", key, e);
            ModdedObjectUndo.record(owner, level, placer.undoSnapshot());
            IrisModdedCommands.fail(source, "Place failed: " + e.getClass().getSimpleName() + " (partial changes recorded for undo)");
            return 0;
        }
        ModdedObjectUndo.record(owner, level, placer.undoSnapshot());
        String tileNote = ModdedObjectCommands.tileNote(placer);
        IrisModdedCommands.ok(source, "Placed '" + key + "' (" + pieces.size() + " pieces, " + placer.writes() + " write(s)" + tileNote + ") at your location. /iris object undo to revert.");
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestIrisStructureKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = IrisModdedCommands.engineFor(context.getSource().getLevel());
            if (engine != null && engine.getData().getStructureLoader() != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getStructureLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    }
}
