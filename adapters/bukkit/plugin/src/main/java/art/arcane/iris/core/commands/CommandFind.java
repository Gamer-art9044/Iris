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

import art.arcane.iris.Iris;
import art.arcane.iris.platform.bukkit.BukkitPlatform;
import art.arcane.iris.core.service.ObjectStudioSaveService;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.platform.EngineBukkitOps;
import art.arcane.iris.engine.framework.StructureReachability;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.director.specialhandlers.ObjectHandler;
import art.arcane.iris.util.common.director.specialhandlers.StructureHandler;
import art.arcane.iris.util.common.format.C;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.StructureSearchResult;

@Director(name = "find", origin = DirectorOrigin.PLAYER, description = "Iris Find commands", aliases = "goto")
public class CommandFind implements DirectorExecutor {
    @Director(description = "Find a biome")
    public void biome(
            @Param(description = "The biome to look for")
            IrisBiome biome,
            @Param(description = "Should you be teleported", defaultValue = "true")
            boolean teleport
    ) {
        Engine e = engine();

        if (e == null) {
            sender().sendMessage(C.GOLD + "Not in an Iris World!");
            return;
        }

        EngineBukkitOps.gotoBiome(e, biome, player(), teleport);
    }

    @Director(description = "Find a region")
    public void region(
            @Param(description = "The region to look for")
            IrisRegion region,
            @Param(description = "Should you be teleported", defaultValue = "true")
            boolean teleport
    ) {
        Engine e = engine();

        if (e == null) {
            sender().sendMessage(C.GOLD + "Not in an Iris World!");
            return;
        }

        EngineBukkitOps.gotoRegion(e, region, player(), teleport);
    }

    @Director(description = "Find a point of interest.")
    public void poi(
            @Param(description = "The type of PoI to look for.")
            String type,
            @Param(description = "Should you be teleported", defaultValue = "true")
            boolean teleport
    ) {
        Engine e = engine();
        if (e == null) {
            sender().sendMessage(C.GOLD + "Not in an Iris World!");
            return;
        }

        EngineBukkitOps.gotoPOI(e, type, player(), teleport);
    }

    @Director(description = "Find a structure (a vanilla key like minecraft:village_plains or minecraft:stronghold, or an imported iris structure key)", sync = true)
    public void structure(
            @Param(description = "The structure to look for (e.g. minecraft:village_plains, minecraft:stronghold, minecraft_ancient_city)", customHandler = StructureHandler.class)
            String structure
    ) {
        VolmitSender commandSender = sender();
        if (commandSender == null) {
            Iris.reportError("Structure lookup started without a command sender context.", new IllegalStateException("Missing command sender context"));
            return;
        }

        Engine e = engine();

        if (e == null) {
            commandSender.sendMessage(C.GOLD + "Not in an Iris World!");
            return;
        }

        if (IrisStructureLocator.isPlaced(e, structure)) {
            locateIrisStructure(e, structure, commandSender);
            return;
        }

        if (BukkitNativeStructureLocatePolicy.isUnavailable(structure)) {
            commandSender.sendMessage(C.RED + BukkitNativeStructureLocatePolicy.unavailableMessage());
            return;
        }

        Player target = player();
        if (target == null) {
            commandSender.sendMessage(C.GOLD + "Run this in-game to teleport to a structure.");
            return;
        }

        World targetWorld = target.getWorld();
        Location origin = target.getLocation();
        commandSender.sendMessage(C.GRAY + "Locating " + structure + "...");
        J.s(() -> {
            try {
                Registry<Structure> structureRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
                Structure match = null;
                for (Structure candidate : structureRegistry) {
                    NamespacedKey key = structureRegistry.getKey(candidate);
                    if (key != null && key.toString().equalsIgnoreCase(structure)) {
                        match = candidate;
                        break;
                    }
                }
                if (match == null) {
                    sendStructureMessage(target, commandSender, C.RED + "Unknown structure: " + structure);
                    return;
                }
                if (!StructureReachability.isReachable(e, structure)) {
                    KList<String> miss = StructureReachability.missingBiomeKeys(e, structure);
                    sendStructureMessage(target, commandSender,
                            C.YELLOW + structure + " cannot generate in this world (its required biomes are not produced by this pack"
                                    + (miss.isEmpty() ? "" : ": needs " + String.join("/", miss)) + ").");
                    return;
                }
                StructureSearchResult result = targetWorld.locateNearestStructure(origin, match, 100, false);
                if (result == null || result.getLocation() == null) {
                    sendStructureMessage(target, commandSender, C.YELLOW + "No " + structure + " found within range of you.");
                    return;
                }
                prepareStructureTeleport(target, targetWorld, commandSender, structure, result.getLocation(), false);
            } catch (Throwable t) {
                sendStructureMessage(target, commandSender, C.RED + "Could not locate " + structure + ": " + t.getClass().getSimpleName());
                Iris.reportError("Could not locate structure '" + structure + "'.", t);
            }
        });
    }

    private void locateIrisStructure(Engine engine, String structure, VolmitSender commandSender) {
        Player target = player();
        if (target == null) {
            commandSender.sendMessage(C.GOLD + "Run this in-game to teleport to a structure.");
            return;
        }
        World targetWorld = target.getWorld();
        Location origin = target.getLocation();
        int blockX = origin.getBlockX();
        int blockZ = origin.getBlockZ();
        commandSender.sendMessage(C.GRAY + "Locating " + structure + "...");
        J.a(() -> {
            try {
                IrisStructureLocator.LocateResult result =
                        IrisStructureLocator.locate(engine, structure, blockX, blockZ, 1024);
                if (result.status() == IrisStructureLocator.LocateStatus.SEARCH_LIMIT_REACHED) {
                    sendStructureMessage(target, commandSender,
                            C.YELLOW + "Unable to locate " + structure
                                    + ": the density search safety limit was reached before the full 1024-chunk radius was searched.");
                    return;
                }
                if (!result.found()) {
                    sendStructureMessage(target, commandSender,
                            C.YELLOW + "No " + structure + " found within 1024 chunks of you.");
                    return;
                }
                Location destination = new Location(
                        targetWorld, result.originX(), result.baseY(), result.originZ());
                prepareStructureTeleport(target, targetWorld, commandSender, structure, destination, true);
            } catch (Throwable t) {
                sendStructureMessage(target, commandSender,
                        C.RED + "Could not locate " + structure + ": " + t.getClass().getSimpleName());
                Iris.reportError("Could not locate Iris-placed structure '" + structure + "'.", t);
            }
        });
    }

    @Director(description = "Find an object")
    public void object(
            @Param(description = "The object to look for", customHandler = ObjectHandler.class)
            String object,
            @Param(description = "Should you be teleported", defaultValue = "true")
            boolean teleport
    ) {
        Engine e = engine();

        if (e == null) {
            sender().sendMessage(C.GOLD + "Not in an Iris World!");
            return;
        }

        Player studioPlayer = player();
        if (studioPlayer != null) {
            try {
                if (ObjectStudioSaveService.get().teleportTo(studioPlayer, object)) {
                    sender().sendMessage(C.GREEN + "Object Studio: teleporting to " + object);
                    return;
                }
            } catch (Throwable t) {
                Iris.reportError(t);
            }
        }

        if (e.hasObjectPlacement(object)) {
            EngineBukkitOps.gotoObject(e, object, player(), teleport);
            return;
        }

        sender().sendMessage(C.RED + object + " is not configured in any region/biome object placements.");
    }

    private void prepareStructureTeleport(Player target, World world, VolmitSender commandSender, String structure,
                                          Location at, boolean useLocatedY) {
        int chunkX = at.getBlockX() >> 4;
        int chunkZ = at.getBlockZ() >> 4;
        BukkitPlatform.chunkAtAsync(world, chunkX, chunkZ, true).whenComplete((chunk, error) -> {
            if (error != null) {
                sendStructureMessage(target, commandSender, C.RED + "Could not load the destination for " + structure + ".");
                Iris.reportError("Could not load structure destination '" + structure + "'.", error);
                return;
            }
            boolean scheduled = J.runRegion(world, chunkX, chunkZ,
                    () -> teleportToStructure(target, world, commandSender, structure, at, useLocatedY));
            if (!scheduled) {
                sendStructureMessage(target, commandSender, C.RED + "Could not schedule the destination lookup for " + structure + ".");
            }
        });
    }

    private void teleportToStructure(Player target, World world, VolmitSender commandSender, String structure,
                                     Location at, boolean useLocatedY) {
        try {
            int y = useLocatedY
                    ? Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 1, at.getBlockY() + 2))
                    : world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) + 2;
            Location destination = new Location(world, at.getBlockX() + 0.5, y, at.getBlockZ() + 0.5);
            J.runEntity(target, () -> {
                BukkitPlatform.teleportAsync(target, destination);
                commandSender.sendMessage(C.GREEN + "Teleported to " + structure + " @ "
                        + at.getBlockX() + ", " + y + ", " + at.getBlockZ());
            });
        } catch (Throwable t) {
            sendStructureMessage(target, commandSender, C.RED + "Could not prepare the destination for " + structure + ".");
            Iris.reportError("Could not prepare structure destination '" + structure + "'.", t);
        }
    }

    private void sendStructureMessage(Player target, VolmitSender commandSender, String message) {
        J.runEntity(target, () -> commandSender.sendMessage(message));
    }
}
