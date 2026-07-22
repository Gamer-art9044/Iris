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
import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.platform.EngineBukkitOps;
import art.arcane.iris.engine.framework.StructureReachability;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
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

import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.BukkitCommandMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.iris.core.localization.BukkitCommandMessagesExtended;
@Director(name = "find", origin = DirectorOrigin.PLAYER, description = "Iris Find commands", descriptionKey = "iris.director.commandfind.director.iris_find_commands", aliases = "goto")
public class CommandFind implements DirectorExecutor {
    @Director(description = "Find a biome", descriptionKey = "iris.director.commandfind.director.find_biome")
    public void biome(
            @Param(description = "The biome to look for", descriptionKey = "iris.director.commandfind.param.biome_look")
            IrisBiome biome,
            @Param(description = "Should you be teleported", descriptionKey = "iris.director.commandfind.param.should_you_be_teleported", defaultValue = "true")
            boolean teleport
    ) {
        Engine e = engine();

        if (e == null) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_FIND_NOT_IRIS_WORLD));
            return;
        }

        EngineBukkitOps.gotoBiome(e, biome, player(), teleport);
    }

    @Director(description = "Find a region", descriptionKey = "iris.director.commandfind.director.find_region")
    public void region(
            @Param(description = "The region to look for", descriptionKey = "iris.director.commandfind.param.region_look")
            IrisRegion region,
            @Param(description = "Should you be teleported", descriptionKey = "iris.director.commandfind.param.should_you_be_teleported_2", defaultValue = "true")
            boolean teleport
    ) {
        Engine e = engine();

        if (e == null) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_FIND_NOT_IRIS_WORLD_2));
            return;
        }

        EngineBukkitOps.gotoRegion(e, region, player(), teleport);
    }

    @Director(description = "Find a point of interest.", descriptionKey = "iris.director.commandfind.director.find_point_interest")
    public void poi(
            @Param(description = "The type of PoI to look for.", descriptionKey = "iris.director.commandfind.param.type_poi_look")
            String type,
            @Param(description = "Should you be teleported", descriptionKey = "iris.director.commandfind.param.should_you_be_teleported_3", defaultValue = "true")
            boolean teleport
    ) {
        Engine e = engine();
        if (e == null) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_FIND_NOT_IRIS_WORLD_3));
            return;
        }

        EngineBukkitOps.gotoPOI(e, type, player(), teleport);
    }

    @Director(description = "Find a structure (a vanilla key like minecraft:village_plains or minecraft:stronghold, or an imported iris structure key)", descriptionKey = "iris.director.commandfind.director.find_structure_vanilla_key_like_minecraft_village_plains_minecraft_stronghold_imported_iris", sync = true)
    public void structure(
            @Param(description = "The structure to look for (e.g. minecraft:village_plains, minecraft:stronghold, minecraft_ancient_city)", descriptionKey = "iris.director.commandfind.param.structure_look_e_g_minecraft_village_plains_minecraft_stronghold_minecraft_ancient_city", customHandler = StructureHandler.class)
            String structure
    ) {
        VolmitSender commandSender = sender();
        if (commandSender == null) {
            Iris.reportError("Structure lookup started without a command sender context.", new IllegalStateException("Missing command sender context"));
            return;
        }

        Engine e = engine();

        if (e == null) {
            commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_FIND_NOT_IRIS_WORLD));
            return;
        }

        String structureKey = structure == null ? "" : structure.trim();
        Structure nativeStructure = resolveNativeStructure(structureKey);
        if (nativeStructure != null) {
            IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(
                    e, structureKey, false);
            if (!decision.generate()
                    && decision.status() != NativeStructureGenerationStatus.REPLACED_BY_IRIS) {
                commandSender.sendMessage(C.RED + NativeStructureGenerationPolicy.generationStatusMessage(
                        structureKey, decision.status()));
                return;
            }
            if (decision.status() == NativeStructureGenerationStatus.REPLACED_BY_IRIS) {
                locateIrisStructure(e, structureKey, commandSender);
                return;
            }
        }

        if (nativeStructure == null && IrisStructureLocator.isPlaced(e, structureKey)) {
            locateIrisStructure(e, structureKey, commandSender);
            return;
        }

        if (nativeStructure == null) {
            commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_FIND_UNKNOWN_STRUCTURE, MessageArgument.untrusted("structureKey", structureKey)));
            return;
        }

        Player target = player();
        if (target == null) {
            commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_FIND_RUN_THIS_GAME_TELEPORT_STRUCTURE));
            return;
        }

        World targetWorld = target.getWorld();
        Location origin = target.getLocation();
        commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_FIND_LOCATING, MessageArgument.untrusted("structureKey", structureKey)));
        J.s(() -> {
            try {
                if (!StructureReachability.isReachable(e, structureKey)) {
                    KList<String> miss = StructureReachability.missingBiomeKeys(e, structureKey);
                    sendStructureMessage(target, commandSender,
                            C.YELLOW + structureKey + " cannot generate in this world (its required biomes are not produced by this pack"
                                    + (miss.isEmpty() ? "" : ": needs " + String.join("/", miss)) + ").");
                    return;
                }
                StructureSearchResult result = targetWorld.locateNearestStructure(
                        origin, nativeStructure, 100, false);
                if (result == null || result.getLocation() == null) {
                    sendStructureMessage(target, commandSender,
                            C.YELLOW + "No " + structureKey + " found within range of you.");
                    return;
                }
                prepareStructureTeleport(
                        target, targetWorld, commandSender, structureKey, result.getLocation(), false);
            } catch (Throwable t) {
                sendStructureMessage(target, commandSender,
                        C.RED + "Could not locate " + structureKey + ": " + t.getClass().getSimpleName());
                Iris.reportError("Could not locate structure '" + structureKey + "'.", t);
            }
        });
    }

    private static Structure resolveNativeStructure(String structureKey) {
        Registry<Structure> structureRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
        for (Structure candidate : structureRegistry) {
            NamespacedKey key = structureRegistry.getKey(candidate);
            if (key != null && key.toString().equalsIgnoreCase(structureKey)) {
                return candidate;
            }
        }
        return null;
    }

    private void locateIrisStructure(Engine engine, String structure, VolmitSender commandSender) {
        Player target = player();
        if (target == null) {
            commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_FIND_RUN_THIS_GAME_TELEPORT_STRUCTURE_2));
            return;
        }
        World targetWorld = target.getWorld();
        Location origin = target.getLocation();
        int blockX = origin.getBlockX();
        int blockZ = origin.getBlockZ();
        commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_FIND_LOCATING_2, MessageArgument.untrusted("structure", structure)));
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

    @Director(description = "Find an object", descriptionKey = "iris.director.commandfind.director.find_object")
    public void object(
            @Param(description = "The object to look for", descriptionKey = "iris.director.commandfind.param.object_look", customHandler = ObjectHandler.class)
            String object,
            @Param(description = "Should you be teleported", descriptionKey = "iris.director.commandfind.param.should_you_be_teleported_4", defaultValue = "true")
            boolean teleport
    ) {
        Engine e = engine();

        if (e == null) {
            sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_FIND_NOT_IRIS_WORLD_4));
            return;
        }

        Player studioPlayer = player();
        if (studioPlayer != null) {
            try {
                if (ObjectStudioSaveService.get().teleportTo(studioPlayer, object)) {
                    sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_FIND_OBJECT_STUDIO_TELEPORTING, MessageArgument.untrusted("object", object)));
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

        sender().sendMessage(IrisLanguage.text(BukkitCommandMessagesExtended.COMMAND_FIND_IS_NOT_CONFIGURED_ANY_REGION_BIOME_OBJECT_PLACEMENTS, MessageArgument.untrusted("object", object)));
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
                commandSender.sendMessage(IrisLanguage.text(BukkitCommandMessages.COMMAND_FIND_TELEPORTED, MessageArgument.untrusted("structure", structure), MessageArgument.untrusted("value", at.getBlockX()), MessageArgument.untrusted("y", y), MessageArgument.untrusted("value2", at.getBlockZ())));
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
