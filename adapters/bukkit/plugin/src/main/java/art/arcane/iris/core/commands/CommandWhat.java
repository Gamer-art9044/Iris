/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.commands;

import art.arcane.iris.platform.bukkit.BukkitBlockResolution;

import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.Iris;
import art.arcane.iris.core.edit.BlockSignal;
import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.platform.EngineBukkitOps;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.matter.MatterMarker;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.BukkitCommandMessagesExtended;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
@Director(name = "what", origin = DirectorOrigin.PLAYER, description = "Iris What?", descriptionKey = "iris.director.commandwhat.director.iris_what")
public class CommandWhat implements DirectorExecutor {
    @Director(description = "What is in my hand?", descriptionKey = "iris.director.commandwhat.director.what_is_my_hand", origin = DirectorOrigin.PLAYER)
    public void hand() {
        try {
            BlockData bd = player().getInventory().getItemInMainHand().getType().createBlockData();
            if (!bd.getMaterial().equals(Material.AIR)) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_MATERIAL, MessageArgument.untrusted("value", bd.getMaterial().name())));
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_FULL, MessageArgument.untrusted("value", bd.getAsString(true))));
            } else {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_PLEASE_HOLD_BLOCK_ITEM));
            }
        } catch (Throwable e) {
            Iris.reportError(e);
            Material bd = player().getInventory().getItemInMainHand().getType();
            if (!bd.equals(Material.AIR)) {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_MATERIAL_2, MessageArgument.untrusted("value", bd.name())));
            } else {
                sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_PLEASE_HOLD_BLOCK_ITEM_2));
            }
        }
    }

    @Director(description = "What biome am i in?", descriptionKey = "iris.director.commandwhat.director.what_biome_am_i", origin = DirectorOrigin.PLAYER)
    public void biome() {
        try {
            IrisBiome b = engine().getBiome(player().getLocation().getBlockX(), player().getLocation().getBlockY() - player().getWorld().getMinHeight(), player().getLocation().getBlockZ());
            Biome derivative = b.getDerivative();
            NamespacedKey derivativeKey = resolveBiomeKey(derivative);
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_IBIOME, MessageArgument.untrusted("value", b.getLoadKey()), MessageArgument.untrusted("value2", derivativeKey == null ? IrisLanguage.plain(RuntimeUiMessages.STATUS_UNREGISTERED) : derivativeKey.getKey())));

        } catch (Throwable e) {
            Iris.reportError(e);
            Biome biome = player().getLocation().getBlock().getBiome();
            NamespacedKey key = resolveBiomeKey(biome);
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_NON_IRIS_BIOME, MessageArgument.untrusted("value", key == null ? IrisLanguage.plain(RuntimeUiMessages.STATUS_UNREGISTERED) : key)));

            if (key == null || key.getKey().equals("custom")) {
                try {
                    sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_DATA_PACK_BIOME_ID, MessageArgument.untrusted("value", INMS.get().getTrueBiomeBaseKey(player().getLocation())), MessageArgument.untrusted("value2", INMS.get().getTrueBiomeBaseId(INMS.get().getTrueBiomeBase(player().getLocation())))));
                } catch (Throwable ee) {
                    Iris.reportError(ee);
                }
            }
        }
    }

    @Director(description = "What region am i in?", descriptionKey = "iris.director.commandwhat.director.what_region_am_i", origin = DirectorOrigin.PLAYER)
    public void region() {
        VolmitSender commandSender = sender();
        Player player = player();
        World world = world();
        Engine engine = engine();

        // Chunk access must happen on the thread owning the player's chunk.
        onPlayerThread(player, () -> {
            try {
                Chunk chunk = world.getChunkAt(player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
                IrisRegion r = EngineBukkitOps.getRegion(engine, chunk);
                commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_IREGION, MessageArgument.untrusted("value", r.getLoadKey()), MessageArgument.untrusted("value2", r.getName())));

            } catch (Throwable e) {
                Iris.reportError(e);
                commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_IRIS_WORLDS_ONLY));
            }
        });
    }

    @Director(description = "What block am i looking at?", descriptionKey = "iris.director.commandwhat.director.what_block_am_i_looking_at", origin = DirectorOrigin.PLAYER)
    public void block() {
        VolmitSender commandSender = sender();
        Player player = player();

        // The raycast reads blocks, so it has to run on the thread owning the player.
        onPlayerThread(player, () -> {
            BlockData bd;
            try {
                bd = player.getTargetBlockExact(128, FluidCollisionMode.NEVER).getBlockData();
            } catch (NullPointerException e) {
                Iris.reportError(e);
                commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_PLEASE_LOOK_AT_ANY_BLOCK_NOT_AT_SKY));
                bd = null;
            }

            if (bd != null) {
                commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_MATERIAL_3, MessageArgument.untrusted("value", bd.getMaterial().name())));
                commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_FULL_2, MessageArgument.untrusted("value", bd.getAsString(true))));

                if (BukkitBlockResolution.isStorage(bd)) {
                    commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_STORAGE_BLOCK_LOOT_CAPABLE));
                }

                if (BukkitBlockResolution.isLit(bd)) {
                    commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_LIT_BLOCK_LIGHT_CAPABLE));
                }

                if (BukkitBlockResolution.isFoliage(bd)) {
                    commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_FOLIAGE_BLOCK));
                }

                if (BukkitBlockResolution.isDecorant(bd)) {
                    commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_DECORANT_BLOCK));
                }

                if (BukkitBlockResolution.isFluid(bd)) {
                    commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_FLUID_BLOCK));
                }

                if (BukkitBlockResolution.isFoliagePlantable(bd)) {
                    commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_PLANTABLE_FOLIAGE_BLOCK));
                }

                if (BukkitBlockResolution.isSolid(bd)) {
                    commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_SOLID_BLOCK));
                }
            }
        });
    }

    @Director(description = "Show markers in chunk", descriptionKey = "iris.director.commandwhat.director.show_markers_chunk", origin = DirectorOrigin.PLAYER)
    public void markers(@Param(description = "Marker name such as cave_floor or cave_ceiling", descriptionKey = "iris.director.commandwhat.param.marker_name_such_as_cave_floor_cave_ceiling") String marker) {
        VolmitSender commandSender = sender();
        Player player = player();

        // Chunk lookup plus the block signals both need the thread owning the player's chunk.
        onPlayerThread(player, () -> {
            Chunk c = player.getLocation().getChunk();

            if (IrisToolbelt.isIrisWorld(c.getWorld())) {
                AtomicInteger v = new AtomicInteger(0);

                for (int xxx = c.getX() - 4; xxx <= c.getX() + 4; xxx++) {
                    for (int zzz = c.getZ() - 4; zzz <= c.getZ() + 4; zzz++) {
                        IrisToolbelt.access(c.getWorld()).getEngine().getMantle().findMarkers(xxx, zzz, new MatterMarker(marker))
                                .convert((i) -> BukkitPlatform.toLocation(i, c.getWorld())).forEach((i) -> {
                                    BlockSignal.of(i.getWorld(), i.getBlockX(), i.getBlockY(), i.getBlockZ(), 100);
                                    v.incrementAndGet();
                                });
                    }
                }

                commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_FOUND_NEARBY_MARKERS, MessageArgument.untrusted("value", v.get()), MessageArgument.untrusted("marker", marker)));
            } else {
                commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_WHAT_IRIS_WORLDS_ONLY_2));
            }
        });
    }

    /**
     * Runs the body on the thread owning the player, reporting when the hop cannot be scheduled.
     */
    private void onPlayerThread(Player player, Runnable body) {
        if (player == null) {
            return;
        }

        if (!J.runEntity(player, body)) {
            Iris.warn("Could not schedule /iris what on the thread owning " + player.getName() + ".");
        }
    }

    private NamespacedKey resolveBiomeKey(Biome biome) {
        Object keyOrNullValue = invokeNoThrow(biome, "getKeyOrNull");
        if (keyOrNullValue instanceof NamespacedKey namespacedKey) {
            return namespacedKey;
        }

        Object keyOrThrowValue = invokeNoThrow(biome, "getKeyOrThrow");
        if (keyOrThrowValue instanceof NamespacedKey namespacedKey) {
            return namespacedKey;
        }

        Object keyValue = invokeNoThrow(biome, "getKey");
        if (keyValue instanceof NamespacedKey namespacedKey) {
            return namespacedKey;
        }

        return null;
    }

    private Object invokeNoThrow(Biome biome, String methodName) {
        if (biome == null) {
            return null;
        }

        try {
            Method method = biome.getClass().getMethod(methodName);
            return method.invoke(biome);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
