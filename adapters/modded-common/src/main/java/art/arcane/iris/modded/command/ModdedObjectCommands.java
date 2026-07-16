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
import art.arcane.iris.core.tools.TreePlausibilizeBatch;
import art.arcane.iris.core.tools.TreePlausibilizer;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisObjectRotation;
import art.arcane.iris.engine.object.TileData;
import art.arcane.iris.modded.ModdedBlockState;
import art.arcane.iris.modded.ModdedTileData;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.math.RNG;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public final class ModdedObjectCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final Predicate<CommandSourceStack> GATE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final long MAX_SAVE_VOLUME = 500000L;
    private static final long MAX_AUTOSELECT_VOLUME = 100000L;
    private static final double TARGET_RANGE = 256.0D;

    private static final SuggestionProvider<CommandSourceStack> OBJECT_KEYS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> {
        ModdedCommandFeedback.tab(context.getSource());
        try {
            Engine engine = IrisModdedCommands.engineFor(context.getSource().getLevel());
            if (engine != null) {
                return SharedSuggestionProvider.suggest(engine.getData().getObjectLoader().getPossibleKeys(), builder);
            }
        } catch (Throwable ignored) {
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> ROTATIONS = (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) ->
            SharedSuggestionProvider.suggest(List.of("0", "90", "180", "270"), builder);

    private ModdedObjectCommands() {
    }

    private enum ResizeOp {
        EXPAND,
        CONTRACT,
        SHIFT
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
        ModdedObjectUndo.init();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(GATE);

        root.executes((CommandContext<CommandSourceStack> context) -> ModdedCommandHelp.send(context.getSource(), name));

        root.then(Commands.literal("wand")
                .executes((CommandContext<CommandSourceStack> context) -> giveWand(context.getSource())));
        root.then(Commands.literal("dust")
                .executes((CommandContext<CommandSourceStack> context) -> giveDust(context.getSource())));
        root.then(Commands.literal("d")
                .executes((CommandContext<CommandSourceStack> context) -> giveDust(context.getSource())));

        root.then(Commands.literal("save")
                .then(Commands.literal("overwrite")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes((CommandContext<CommandSourceStack> context) -> save(context.getSource(), StringArgumentType.getString(context, "name"), true))))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes((CommandContext<CommandSourceStack> context) -> save(context.getSource(), StringArgumentType.getString(context, "name"), false))));

        root.then(pasteTree());

        root.then(resizeTree("expand", ResizeOp.EXPAND));
        root.then(resizeTree("contract", ResizeOp.CONTRACT));
        root.then(resizeTree("-", ResizeOp.CONTRACT));
        root.then(resizeTree("shift", ResizeOp.SHIFT));

        root.then(Commands.literal("xpy")
                .executes((CommandContext<CommandSourceStack> context) -> autoSelect(context.getSource(), false)));
        root.then(Commands.literal("x+y")
                .executes((CommandContext<CommandSourceStack> context) -> autoSelect(context.getSource(), false)));
        root.then(Commands.literal("xay")
                .executes((CommandContext<CommandSourceStack> context) -> autoSelect(context.getSource(), true)));
        root.then(Commands.literal("x&y")
                .executes((CommandContext<CommandSourceStack> context) -> autoSelect(context.getSource(), true)));

        root.then(positionTree("position1", true));
        root.then(positionTree("p1", true));
        root.then(positionTree("position2", false));
        root.then(positionTree("p2", false));

        root.then(Commands.literal("analyze")
                .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                        .executes((CommandContext<CommandSourceStack> context) -> analyze(context.getSource(), StringArgumentType.getString(context, "key")))));

        root.then(Commands.literal("shrink")
                .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                        .executes((CommandContext<CommandSourceStack> context) -> shrink(context.getSource(), StringArgumentType.getString(context, "key")))));

        root.then(Commands.literal("undo")
                .executes((CommandContext<CommandSourceStack> context) -> undo(context.getSource(), 1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 32))
                        .executes((CommandContext<CommandSourceStack> context) -> undo(context.getSource(), IntegerArgumentType.getInteger(context, "amount")))));
        root.then(Commands.literal("u")
                .executes((CommandContext<CommandSourceStack> context) -> undo(context.getSource(), 1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 32))
                        .executes((CommandContext<CommandSourceStack> context) -> undo(context.getSource(), IntegerArgumentType.getInteger(context, "amount")))));

        root.then(bukkitOnly("we", "WorldEdit selection import requires the Bukkit plugin with WorldEdit installed."));
        root.then(bukkitOnly("studio", "The object studio world requires the Bukkit studio toolchain; it is not available on modded servers."));
        root.then(bukkitOnly("convert", "Schematic conversion (.schem -> .iob) requires the Bukkit plugin."));
        root.then(Commands.literal("plausibilize")
                .then(Commands.argument("args", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                        .executes((CommandContext<CommandSourceStack> context) -> plausibilize(context.getSource(), StringArgumentType.getString(context, "args")))));

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pasteTree() {
        LiteralArgumentBuilder<CommandSourceStack> paste = Commands.literal("paste");
        paste.then(Commands.argument("key", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                .executes((CommandContext<CommandSourceStack> context) -> paste(context.getSource(), StringArgumentType.getString(context, "key"), 0, null)));
        paste.then(Commands.literal("rotate")
                .then(Commands.argument("degrees", IntegerArgumentType.integer(-270, 270)).suggests(ROTATIONS)
                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                                .executes((CommandContext<CommandSourceStack> context) -> paste(context.getSource(), StringArgumentType.getString(context, "key"),
                                        IntegerArgumentType.getInteger(context, "degrees"), null)))));
        paste.then(Commands.literal("at")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.literal("rotate")
                                                .then(Commands.argument("degrees", IntegerArgumentType.integer(-270, 270)).suggests(ROTATIONS)
                                                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                                                                .executes((CommandContext<CommandSourceStack> context) -> paste(context.getSource(), StringArgumentType.getString(context, "key"),
                                                                        IntegerArgumentType.getInteger(context, "degrees"),
                                                                        new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z")))))))
                                        .then(Commands.argument("key", StringArgumentType.greedyString()).suggests(OBJECT_KEYS)
                                                .executes((CommandContext<CommandSourceStack> context) -> paste(context.getSource(), StringArgumentType.getString(context, "key"), 0,
                                                        new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z")))))))));
        return paste;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resizeTree(String name, ResizeOp op) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> resize(context.getSource(), 1, op))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 256))
                        .executes((CommandContext<CommandSourceStack> context) -> resize(context.getSource(), IntegerArgumentType.getInteger(context, "amount"), op)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> positionTree(String name, boolean first) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> position(context.getSource(), first, false))
                .then(Commands.literal("look")
                        .executes((CommandContext<CommandSourceStack> context) -> position(context.getSource(), first, true)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> bukkitOnly(String name, String message) {
        return Commands.literal(name)
                .executes((CommandContext<CommandSourceStack> context) -> {
                    IrisModdedCommands.fail(context.getSource(), message);
                    return 0;
                });
    }

    public static int giveWand(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players. (the wand is a physical item)");
            return 0;
        }
        if (!player.getInventory().add(ModdedWandService.createWand())) {
            IrisModdedCommands.fail(source, "Your inventory is full.");
            return 0;
        }
        IrisModdedCommands.ok(source, "Poof! Good luck building! (left click = corner 1, right click = corner 2)");
        return 1;
    }

    private static int giveDust(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players. (the dust is a physical item)");
            return 0;
        }
        if (!player.getInventory().add(ModdedWandService.createDust())) {
            IrisModdedCommands.fail(source, "Your inventory is full.");
            return 0;
        }
        IrisModdedCommands.ok(source, "Right click a block to reveal the object it belongs to.");
        return 1;
    }

    private static int save(CommandSourceStack source, String nameRaw, boolean overwrite) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players. (saving captures your wand selection)");
            return 0;
        }
        ServerLevel level = player.level();
        Engine engine = IrisModdedCommands.engineFor(level);
        if (engine == null) {
            IrisModdedCommands.fail(source, "This dimension is not generated by Iris; objects save into the dimension's pack.");
            return 0;
        }
        if (!ModdedWandService.isHoldingWand(player)) {
            IrisModdedCommands.fail(source, "Hold your Iris wand. (/iris wand)");
            return 0;
        }
        ModdedWandService.Selection selection = ModdedWandService.selection(player);
        if (selection == null) {
            IrisModdedCommands.fail(source, "No area selected. Left/right click blocks with the wand first.");
            return 0;
        }
        String name = nameRaw.trim().replace('\\', '/');
        if (name.isEmpty() || name.contains("..")) {
            IrisModdedCommands.fail(source, "Invalid object name: " + nameRaw);
            return 0;
        }
        BlockPos min = selection.min();
        BlockPos max = selection.max();
        int w = max.getX() - min.getX() + 1;
        int h = max.getY() - min.getY() + 1;
        int d = max.getZ() - min.getZ() + 1;
        long volume = (long) w * h * d;
        if (volume > MAX_SAVE_VOLUME) {
            IrisModdedCommands.fail(source, "Selection too large: " + volume + " blocks (max " + MAX_SAVE_VOLUME + ").");
            return 0;
        }
        File file = new File(engine.getData().getDataFolder(), "objects" + File.separator + name.replace('/', File.separatorChar) + ".iob");
        if (file.exists() && !overwrite) {
            IrisModdedCommands.fail(source, "File already exists. Use /iris object save overwrite " + name);
            return 0;
        }
        int[] tilesSkipped = {0};
        int[] tilesSaved = {0};
        IrisObject object = capture(level, min, max, w, h, d, tilesSkipped, tilesSaved);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            object.write(file);
        } catch (IOException e) {
            LOGGER.error("Iris object save failed for {}", file.getAbsolutePath(), e);
            IrisModdedCommands.fail(source, "Failed to save object: " + e.getMessage());
            return 0;
        }
        StringBuilder tileNote = new StringBuilder();
        if (tilesSaved[0] > 0) {
            tileNote.append(" (").append(tilesSaved[0]).append(" tile entity state(s) captured");
            if (tilesSkipped[0] > 0) {
                tileNote.append(", ").append(tilesSkipped[0]).append(" failed");
            }
            tileNote.append(")");
        } else if (tilesSkipped[0] > 0) {
            tileNote.append(" (").append(tilesSkipped[0]).append(" tile state(s) could not be captured)");
        }
        IrisModdedCommands.ok(source, "Saved " + engine.getData().getDataFolder().getName() + "/objects/" + name + ".iob: "
                + w + "x" + h + "x" + d + ", " + object.getBlocks().size() + " block(s)" + tileNote);
        LOGGER.info("Iris object save: {} {}x{}x{} blocks={} tilesSaved={} tilesSkipped={} -> {}", name, w, h, d, object.getBlocks().size(), tilesSaved[0], tilesSkipped[0], file.getAbsolutePath());
        return 1;
    }

    private static IrisObject capture(ServerLevel level, BlockPos min, BlockPos max, int w, int h, int d, int[] tilesSkipped, int[] tilesSaved) {
        IrisObject object = new IrisObject(w, h, d);
        HolderLookup.Provider provider = level.registryAccess();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockState state = level.getBlockState(cursor.set(x, y, z));
                    if (state.is(Blocks.AIR)) {
                        continue;
                    }
                    int ox = x - min.getX();
                    int oy = y - min.getY();
                    int oz = z - min.getZ();
                    object.setUnsigned(ox, oy, oz, ModdedBlockState.of(state, null));
                    if (state.hasBlockEntity()) {
                        TileData tile = captureTile(level, provider, cursor.immutable(), state);
                        if (tile != null) {
                            object.setUnsignedTile(ox, oy, oz, tile);
                            tilesSaved[0]++;
                        } else {
                            tilesSkipped[0]++;
                        }
                    }
                }
            }
        }
        return object;
    }

    private static TileData captureTile(ServerLevel level, HolderLookup.Provider provider, BlockPos pos, BlockState state) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        try {
            CompoundTag tag = blockEntity.saveWithFullMetadata(provider);
            String snbt = NbtUtils.structureToSnbt(tag);
            String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return ModdedTileData.capture(blockKey, snbt);
        } catch (Throwable e) {
            LOGGER.error("Iris tile capture failed at {} {} {}", pos.getX(), pos.getY(), pos.getZ(), e);
            return null;
        }
    }

    private static int paste(CommandSourceStack source, String keyRaw, int rotation, BlockPos at) {
        ServerLevel level = source.getLevel();
        Engine engine = IrisModdedCommands.engineFor(level);
        String key = keyRaw.trim();
        IrisObject object = null;
        try {
            object = IrisData.loadAnyObject(key, engine == null ? null : engine.getData());
        } catch (Throwable e) {
            LOGGER.error("Iris object load failed for {}", key, e);
        }
        if (object == null || object.getBlocks().size() == 0) {
            IrisModdedCommands.fail(source, "Unknown or empty object: " + key);
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        BlockPos target = at;
        if (target == null) {
            if (player == null) {
                IrisModdedCommands.fail(source, "Console must specify coordinates: /iris object paste at <x> <y> <z> [rotate <degrees>] " + key);
                return 0;
            }
            HitResult hit = player.pick(TARGET_RANGE, 1.0F, false);
            if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
                IrisModdedCommands.fail(source, "You are not looking at a block within " + (int) TARGET_RANGE + " blocks.");
                return 0;
            }
            target = blockHit.getBlockPos().above();
        }

        IrisObjectPlacement placement = new IrisObjectPlacement();
        placement.setRotation(IrisObjectRotation.of(0, rotation, 0));
        ModdedObjectPlacer placer = new ModdedObjectPlacer(level, engine);
        try {
            object.place(target.getX(), target.getY() + object.getCenter().getY(), target.getZ(), placer, placement, new RNG(), null);
        } catch (Throwable e) {
            LOGGER.error("Iris paste failed for {}", key, e);
            ModdedObjectUndo.record(player == null ? ModdedObjectUndo.CONSOLE : player.getUUID(), level, placer.undoSnapshot());
            IrisModdedCommands.fail(source, "Paste failed: " + e.getClass().getSimpleName() + " (partial changes recorded for undo)");
            return 0;
        }
        UUID owner = player == null ? ModdedObjectUndo.CONSOLE : player.getUUID();
        ModdedObjectUndo.record(owner, level, placer.undoSnapshot());
        String tileNote = tileNote(placer);
        IrisModdedCommands.ok(source, "Placed " + key + " at " + target.getX() + " " + target.getY() + " " + target.getZ()
                + " rot=" + rotation + " (" + placer.writes() + " write(s), " + placer.nonAirWrites() + " non-air" + tileNote + ")");
        LOGGER.info("Iris paste: {} at {},{},{} rot={} writes={} nonAir={} tilesRestored={} tilesSkipped={}",
                key, target.getX(), target.getY(), target.getZ(), rotation, placer.writes(), placer.nonAirWrites(), placer.restoredTiles(), placer.skippedTiles());
        return placer.writes() > 0 ? 1 : 0;
    }

    private static int resize(CommandSourceStack source, int amount, ResizeOp op) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players.");
            return 0;
        }
        if (!ModdedWandService.isHoldingWand(player)) {
            IrisModdedCommands.fail(source, "Hold your Iris wand. (/iris wand)");
            return 0;
        }
        ModdedWandService.Selection selection = ModdedWandService.selection(player);
        if (selection == null) {
            IrisModdedCommands.fail(source, "No area selected.");
            return 0;
        }
        Direction direction = Direction.getApproximateNearest(player.getLookAngle());
        int[] mins = {selection.min().getX(), selection.min().getY(), selection.min().getZ()};
        int[] maxs = {selection.max().getX(), selection.max().getY(), selection.max().getZ()};
        int axis = switch (direction.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        int step = direction.getAxisDirection().getStep();
        switch (op) {
            case EXPAND -> {
                if (step > 0) {
                    maxs[axis] += amount;
                } else {
                    mins[axis] -= amount;
                }
            }
            case CONTRACT -> {
                if (step > 0) {
                    maxs[axis] = Math.max(mins[axis], maxs[axis] - amount);
                } else {
                    mins[axis] = Math.min(maxs[axis], mins[axis] + amount);
                }
            }
            case SHIFT -> {
                mins[axis] += step * amount;
                maxs[axis] += step * amount;
            }
        }
        BlockPos first = new BlockPos(mins[0], mins[1], mins[2]);
        BlockPos second = new BlockPos(maxs[0], maxs[1], maxs[2]);
        ModdedWandService.setSelection(player, first, second);
        IrisModdedCommands.ok(source, op.name().toLowerCase(Locale.ROOT) + " " + amount + " " + direction.getName()
                + ": " + describe(first, second));
        return 1;
    }

    private static int position(CommandSourceStack source, boolean first, boolean look) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players.");
            return 0;
        }
        if (!ModdedWandService.isHoldingWand(player)) {
            IrisModdedCommands.fail(source, "Ready your wand. (/iris wand)");
            return 0;
        }
        BlockPos pos;
        if (look) {
            HitResult hit = player.pick(TARGET_RANGE, 1.0F, false);
            if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
                IrisModdedCommands.fail(source, "You are not looking at a block.");
                return 0;
            }
            pos = blockHit.getBlockPos();
        } else {
            pos = player.blockPosition().below();
        }
        ModdedWandService.Selection selection = ModdedWandService.selection(player);
        BlockPos other = selection == null ? null : (first ? selection.second() : selection.first());
        BlockPos fallback = other == null ? pos : other;
        if (first) {
            ModdedWandService.setSelection(player, pos, fallback);
        } else {
            ModdedWandService.setSelection(player, fallback, pos);
        }
        IrisModdedCommands.ok(source, "Position " + (first ? 1 : 2) + " set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        return 1;
    }

    private static int autoSelect(CommandSourceStack source, boolean down) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            IrisModdedCommands.fail(source, "This command can only be used by players.");
            return 0;
        }
        if (!ModdedWandService.isHoldingWand(player)) {
            IrisModdedCommands.fail(source, "Hold your wand!");
            return 0;
        }
        ModdedWandService.Selection selection = ModdedWandService.selection(player);
        if (selection == null) {
            IrisModdedCommands.fail(source, "No area selected.");
            return 0;
        }
        ServerLevel level = player.level();
        BlockPos min = selection.min();
        BlockPos max = selection.max();
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > MAX_AUTOSELECT_VOLUME) {
            IrisModdedCommands.fail(source, "Selection too large for auto-select: " + volume + " blocks (max " + MAX_AUTOSELECT_VOLUME + ").");
            return 0;
        }
        int levelMinY = level.getMinY();
        int levelMaxY = levelMinY + level.getHeight() - 1;

        int topMinY = min.getY();
        int topMaxY = max.getY();
        while (topMaxY < levelMaxY && !boxOnlyAir(level, min.getX(), topMinY, min.getZ(), max.getX(), topMaxY, max.getZ())) {
            topMinY++;
            topMaxY++;
        }
        topMaxY--;

        int bottomY = min.getY();
        if (down) {
            int lowMinY = min.getY();
            int lowMaxY = max.getY();
            while (lowMinY > levelMinY && !boxOnlyAir(level, min.getX(), lowMinY, min.getZ(), max.getX(), lowMaxY, max.getZ())) {
                lowMinY--;
                lowMaxY--;
            }
            bottomY = lowMinY + 1;
        }

        int minX = min.getX();
        int maxX = max.getX();
        int minZ = min.getZ();
        int maxZ = max.getZ();
        while (minX < maxX && boxOnlyAir(level, minX, bottomY, minZ, minX, topMaxY, maxZ)) {
            minX++;
        }
        while (maxX > minX && boxOnlyAir(level, maxX, bottomY, minZ, maxX, topMaxY, maxZ)) {
            maxX--;
        }
        while (minZ < maxZ && boxOnlyAir(level, minX, bottomY, minZ, maxX, topMaxY, minZ)) {
            minZ++;
        }
        while (maxZ > minZ && boxOnlyAir(level, minX, bottomY, maxZ, maxX, topMaxY, maxZ)) {
            maxZ--;
        }

        BlockPos first = new BlockPos(minX, bottomY, minZ);
        BlockPos second = new BlockPos(maxX, topMaxY, maxZ);
        ModdedWandService.setSelection(player, first, second);
        IrisModdedCommands.ok(source, "Auto-select complete: " + describe(first, second));
        return 1;
    }

    private static boolean boxOnlyAir(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!level.getBlockState(cursor.set(x, y, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int analyze(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = IrisModdedCommands.engineFor(level);
        String key = keyRaw.trim();
        IrisObject object = null;
        try {
            object = IrisData.loadAnyObject(key, engine == null ? null : engine.getData());
        } catch (Throwable e) {
            LOGGER.error("Iris object load failed for {}", key, e);
        }
        if (object == null) {
            IrisModdedCommands.fail(source, "Unknown object: " + key);
            return 0;
        }
        IrisModdedCommands.ok(source, "Object Size: " + object.getW() + " * " + object.getH() + " * " + object.getD());
        IrisModdedCommands.ok(source, "Blocks Used: " + object.getBlocks().size());
        Map<String, Integer> counts = new HashMap<>();
        Iterator<PlatformBlockState> values = object.getBlocks().values();
        while (values.hasNext()) {
            PlatformBlockState state = values.next();
            counts.merge(state.key(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed());
        IrisModdedCommands.ok(source, "== Blocks in object ==");
        int shown = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            IrisModdedCommands.ok(source, " - " + entry.getKey() + " * " + entry.getValue());
            shown++;
            if (shown >= 10) {
                int remaining = sorted.size() - shown;
                if (remaining > 0) {
                    IrisModdedCommands.ok(source, "  + " + remaining + " other block state(s)");
                }
                break;
            }
        }
        return 1;
    }

    private static int shrink(CommandSourceStack source, String keyRaw) {
        ServerLevel level = source.getLevel();
        Engine engine = IrisModdedCommands.engineFor(level);
        String key = keyRaw.trim();
        IrisObject object = null;
        try {
            object = IrisData.loadAnyObject(key, engine == null ? null : engine.getData());
        } catch (Throwable e) {
            LOGGER.error("Iris object load failed for {}", key, e);
        }
        if (object == null) {
            IrisModdedCommands.fail(source, "Unknown object: " + key);
            return 0;
        }
        IrisModdedCommands.ok(source, "Current Object Size: " + object.getW() + " * " + object.getH() + " * " + object.getD());
        object.shrinkwrap();
        IrisModdedCommands.ok(source, "New Object Size: " + object.getW() + " * " + object.getH() + " * " + object.getD());
        File file = object.getLoadFile();
        if (file == null) {
            IrisModdedCommands.fail(source, "Object has no load file; cannot persist the shrink.");
            return 0;
        }
        try {
            object.write(file);
        } catch (IOException e) {
            LOGGER.error("Iris object shrink save failed for {}", file.getAbsolutePath(), e);
            IrisModdedCommands.fail(source, "Failed to save object " + file.getName() + ": " + e.getMessage());
            return 0;
        }
        return 1;
    }

    private static int plausibilize(CommandSourceStack source, String raw) {
        Engine engine = IrisModdedCommands.engineFor(source.getLevel());
        IrisData data = engine == null ? null : engine.getData();
        boolean dryRun = false;
        int reach = TreePlausibilizer.DEFAULT_REACH;
        StringBuilder targetBuilder = new StringBuilder();
        for (String token : raw.trim().split("\\s+")) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("dryrun=")) {
                dryRun = Boolean.parseBoolean(lower.substring("dryrun=".length()));
                continue;
            }
            if (lower.startsWith("reach=")) {
                try {
                    reach = Integer.parseInt(lower.substring("reach=".length()));
                } catch (NumberFormatException e) {
                    IrisModdedCommands.fail(source, "Invalid reach: " + token);
                    return 0;
                }
                continue;
            }
            if (!targetBuilder.isEmpty()) {
                targetBuilder.append(' ');
            }
            targetBuilder.append(token);
        }
        String target = targetBuilder.toString();
        List<TreePlausibilizeBatch.Target> targets = TreePlausibilizeBatch.resolve(target, data);
        if (targets.isEmpty()) {
            IrisModdedCommands.fail(source, "No objects matched: " + target);
            return 0;
        }
        IrisModdedCommands.ok(source, "Plausibilize [reach=" + reach + (dryRun ? ", DRY" : "")
                + "] queued " + targets.size() + " object(s)");
        boolean dry = dryRun;
        int reachFinal = reach;
        MinecraftServer server = source.getServer();
        J.a(() -> TreePlausibilizeBatch.run(targets, dry, reachFinal, data, (String line) ->
                server.execute(() -> IrisModdedCommands.ok(source, line))));
        return 1;
    }

    private static int undo(CommandSourceStack source, int amount) {
        ServerPlayer player = source.getPlayer();
        UUID owner = player == null ? ModdedObjectUndo.CONSOLE : player.getUUID();
        int available = ModdedObjectUndo.size(owner);
        if (available == 0) {
            IrisModdedCommands.fail(source, "Nothing to undo.");
            return 0;
        }
        int reverted = ModdedObjectUndo.undo(owner, Math.min(amount, available));
        IrisModdedCommands.ok(source, "Reverted " + reverted + " paste(s)!");
        return 1;
    }

    private static String describe(BlockPos first, BlockPos second) {
        return "(" + first.getX() + "," + first.getY() + "," + first.getZ() + ") -> ("
                + second.getX() + "," + second.getY() + "," + second.getZ() + ")";
    }

    static String tileNote(ModdedObjectPlacer placer) {
        StringBuilder note = new StringBuilder();
        if (placer.restoredTiles() > 0) {
            note.append(", ").append(placer.restoredTiles()).append(" tile entity state(s) restored");
        }
        if (placer.skippedTiles() > 0) {
            note.append(", ").append(placer.skippedTiles()).append(" tile state(s) skipped");
        }
        return note.toString();
    }
}
