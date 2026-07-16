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
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.structure.BulkStructureImporter;
import art.arcane.iris.core.structure.StructureCaptureImporter;
import art.arcane.iris.core.structure.StructureImporter;
import art.arcane.iris.core.structure.StructureIndexService;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.IrisStructureLocator;
import art.arcane.iris.engine.framework.NativeStructureGenerationPolicy;
import art.arcane.iris.engine.framework.PlacedStructurePiece;
import art.arcane.iris.engine.framework.StructureAssembler;
import art.arcane.iris.engine.framework.StructureReachability;
import art.arcane.iris.engine.object.IObjectPlacer;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisNativeStructureDecision;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisPosition;
import art.arcane.iris.engine.object.IrisStructure;
import art.arcane.iris.engine.object.NativeStructureGenerationStatus;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.iris.util.common.director.DirectorExecutor;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.iris.util.common.scheduling.J;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.Structure;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Director(name = "structure", aliases = {"struct", "str"}, description = "Iris structure tools (index, import, info)")
public class CommandStructure implements DirectorExecutor {
    @Director(description = "Regenerate structure-index.json listing all vanilla, datapack & iris structures", aliases = {"ls"}, origin = DirectorOrigin.BOTH)
    public void list(
            @Param(description = "The dimension whose pack to index", aliases = "dim")
            IrisDimension dimension
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        File file = StructureIndexService.write(data);
        sender().sendMessage(C.GREEN + "Wrote structure index: " + C.WHITE + file.getPath());
    }

    @Director(name = "import", description = "Import EVERY structure - vanilla AND ingested datapacks - into this pack as editable Iris resources, always overwriting. Rebuilds jigsaw structures (villages, outposts, datapack jigsaws) as editable pool/piece graphs, imports every structure template NBT as an object, and assembles the multi-template structures (shipwrecks, ruined portals, ocean ruins, nether fossils). Run after ingesting a datapack and restarting. Regenerate chunks or use a fresh world for the imported copies to place.", aliases = {"import-all", "reimport", "imp", "all"}, origin = DirectorOrigin.BOTH)
    public void importAll(
            @Param(description = "The dimension whose pack to import into", aliases = "dim")
            IrisDimension dimension
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        sender().sendMessage(C.GREEN + "Importing all vanilla & datapack structures into " + C.WHITE + dimension.getLoadKey() + C.GREEN + " (overwrite)...");
        BulkStructureImporter.Report jigsaws = BulkStructureImporter.importAllVanilla(data, StructureImporter.Mode.OVERWRITE, true, sender());
        BulkStructureImporter.Report templates = BulkStructureImporter.importAllTemplates(data, StructureImporter.Mode.OVERWRITE, sender());
        BulkStructureImporter.Report groups = BulkStructureImporter.importTemplateGroups(data, StructureImporter.Mode.OVERWRITE, sender());
        StructureCaptureImporter.Report captured = StructureCaptureImporter.importAllStructures(data, StructureImporter.Mode.OVERWRITE, sender());
        int imported = jigsaws.imported() + templates.imported() + groups.imported() + captured.imported();
        int failed = jigsaws.failed() + templates.failed() + groups.failed() + captured.failed();
        sender().sendMessage(C.GREEN + "Import complete: " + C.WHITE + imported + C.GREEN + " structures/objects written, " + C.WHITE + failed + C.GREEN + " failed.");
        sender().sendMessage(C.GRAY + "Reference them from a biome/region/dimension 'structures' list, or run /iris structure list " + dimension.getLoadKey() + " to refresh the index. Regenerate chunks for changes to take effect.");
    }

    @Director(name = "capture", description = "Capture code-generated structures that have no NBT template (swamp huts, igloos, etc.) into editable Iris objects by generating each one in a throwaway scratch world and reading back its blocks. Skips structures that already import as a structure, structures wider/taller than the capture cap (strongholds, mansions, monuments stay vanilla), and anything that will not generate in a flat overworld. Each captured structure becomes a single-piece Iris structure you can place from a biome/region/dimension 'structures' list. Runs automatically as the last pass of /iris structure import.", aliases = {"cap"}, origin = DirectorOrigin.BOTH)
    public void capture(
            @Param(description = "The dimension whose pack to capture into", aliases = "dim")
            IrisDimension dimension
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        StructureCaptureImporter.Report report = StructureCaptureImporter.importAllStructures(data, StructureImporter.Mode.OVERWRITE, sender());
        sender().sendMessage(C.GRAY + "Captured " + report.imported() + " structures. Place them from a 'structures' list and regenerate chunks. Delete a structures/*.json to re-capture it.");
    }

    @Director(description = "Verify native structure eligibility and locate Iris-placed structures without running blocking native searches.", aliases = {"locateall"}, origin = DirectorOrigin.BOTH, sync = true)
    public void verify(
            @Param(description = "The dimension to verify", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "Search radius in chunks around the origin (larger is much slower)", defaultValue = "48")
            int radius
    ) {
        World world = resolveIrisWorld(dimension);
        if (world == null) {
            sender().sendMessage(C.RED + "No loaded Iris world found for " + dimension.getLoadKey() + ". Join or create one first (the search runs against a live world).");
            return;
        }
        boolean senderIsPlayer = sender() != null && sender().isPlayer();
        Location center = senderIsPlayer && player().getWorld() == world
                ? player().getLocation()
                : world.getSpawnLocation();
        int searchRadius = Math.max(1, Math.min(radius, 1000));
        PlatformChunkGenerator access = IrisToolbelt.access(world);
        Engine engine = access == null ? null : access.getEngine();
        if (engine == null) {
            sender().sendMessage(C.RED + "The selected Iris world has no active generator engine.");
            return;
        }
        KList<String> structureKeys = new KList<>();
        Registry<Structure> structureRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
        for (Structure structure : structureRegistry) {
            NamespacedKey key = structureRegistry.getKey(structure);
            if (key != null) {
                structureKeys.add(key.toString());
            }
        }
        VolmitSender commandSender = sender();
        Player target = senderIsPlayer ? player() : null;
        commandSender.sendMessage(C.GREEN + "Verifying structures in " + C.WHITE + world.getName()
                + C.GREEN + " from " + center.getBlockX() + "," + center.getBlockZ()
                + " within " + searchRadius + " chunks...");
        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        J.a(() -> runVerification(engine, structureKeys, centerX, centerZ, searchRadius, commandSender, target));
    }

    private void runVerification(Engine engine, KList<String> structureKeys, int centerX, int centerZ,
                                 int searchRadius, VolmitSender commandSender, Player target) {
        KList<String> messages = new KList<>();
        Map<String, IrisNativeStructureDecision> decisions = new HashMap<>(structureKeys.size());
        boolean requiresNativeReachability = false;
        for (String keyName : structureKeys) {
            IrisNativeStructureDecision decision = NativeStructureGenerationPolicy.resolve(engine, keyName, false);
            decisions.put(keyName, decision);
            requiresNativeReachability |= decision.generate();
        }
        Set<String> reachable = Set.of();
        if (requiresNativeReachability) {
            try {
                reachable = StructureReachability.reachableKeys(engine);
            } catch (Throwable error) {
                messages.add(C.RED + "Structure verification could not resolve native biome reachability.");
                sendVerificationMessages(commandSender, target, messages);
                Iris.reportError("Could not resolve native structure biome reachability for verification.", error);
                return;
            }
        }
        int located = 0;
        int nativeEligible = 0;
        int disabled = 0;
        int unreachable = 0;
        int searchLimited = 0;
        int errors = 0;
        for (String keyName : structureKeys) {
            IrisNativeStructureDecision decision = decisions.get(keyName);
            if (decision.status() == NativeStructureGenerationStatus.REPLACED_BY_IRIS) {
                try {
                    IrisStructureLocator.LocateResult result =
                            IrisStructureLocator.locate(engine, keyName, centerX, centerZ, searchRadius);
                    if (result.status() == IrisStructureLocator.LocateStatus.SEARCH_LIMIT_REACHED) {
                        searchLimited++;
                        messages.add(C.YELLOW + "[iris-search-limit] " + C.WHITE + keyName + C.YELLOW
                                + ": unable to complete the density search before its safety limit was reached");
                        continue;
                    }
                    if (!result.found()) {
                        messages.add(C.YELLOW + "[iris-not-found] " + C.WHITE + keyName);
                        continue;
                    }
                    located++;
                    messages.add(C.AQUA + "[iris] " + C.WHITE + keyName + C.GREEN + " @ "
                            + result.originX() + "," + result.baseY() + "," + result.originZ());
                } catch (Throwable error) {
                    errors++;
                    messages.add(C.RED + "[error] " + C.WHITE + keyName + C.RED + ": "
                            + error.getClass().getSimpleName());
                    Iris.reportError("Could not verify Iris-placed structure '" + keyName + "'.", error);
                }
                continue;
            }
            if (!decision.generate()) {
                disabled++;
                messages.add(C.GRAY + "[disabled] " + C.WHITE + keyName);
                continue;
            }
            if (!reachable.contains(keyName.toLowerCase(Locale.ROOT))) {
                unreachable++;
                KList<String> missing = StructureReachability.missingBiomeKeys(engine, keyName);
                messages.add(C.YELLOW + "[unreachable] " + C.WHITE + keyName
                        + (missing.isEmpty() ? "" : C.YELLOW + " needs " + String.join("/", missing)));
                continue;
            }
            nativeEligible++;
            messages.add(C.GREEN + "[native-eligible] " + C.WHITE + keyName);
        }
        messages.add(C.GREEN + "Structure verify: " + C.WHITE + located + C.GREEN + " Iris placements located, "
                + C.WHITE + nativeEligible + C.GREEN + " native structures eligible, "
                + C.WHITE + disabled + C.GREEN + " disabled by policy, "
                + C.WHITE + unreachable + C.GREEN + " biome-unreachable, "
                + C.WHITE + searchLimited + C.GREEN + " density searches safety-limited, "
                + C.WHITE + errors + C.GREEN + " errors. Native eligibility is checked without running blocking live locates.");
        sendVerificationMessages(commandSender, target, messages);
    }

    private void sendVerificationMessages(VolmitSender commandSender, Player target,
                                          KList<String> messages) {
        Runnable send = () -> {
            for (String message : messages) {
                commandSender.sendMessage(message);
            }
        };
        if (target != null) {
            J.runEntity(target, send);
        } else {
            J.s(send);
        }
    }

    private World resolveIrisWorld(IrisDimension dimension) {
        if (sender() != null && sender().isPlayer() && IrisToolbelt.isIrisWorld(player().getWorld())) {
            PlatformChunkGenerator playerGenerator = IrisToolbelt.access(player().getWorld());
            if (matchesDimension(playerGenerator, dimension.getLoadKey())) {
                return player().getWorld();
            }
        }
        for (World w : Bukkit.getWorlds()) {
            if (!IrisToolbelt.isIrisWorld(w)) {
                continue;
            }
            PlatformChunkGenerator gen = IrisToolbelt.access(w);
            if (matchesDimension(gen, dimension.getLoadKey())) {
                return w;
            }
        }
        return null;
    }

    static boolean matchesDimension(PlatformChunkGenerator generator, String dimensionKey) {
        return dimensionKey != null
                && generator != null
                && generator.getEngine() != null
                && generator.getEngine().getDimension() != null
                && dimensionKey.equals(generator.getEngine().getDimension().getLoadKey());
    }

    @Director(description = "Resolve an iris structure's jigsaw graph and report piece count & bounds", origin = DirectorOrigin.BOTH)
    public void info(
            @Param(description = "The dimension whose pack holds the structure", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "The iris structure key to inspect")
            String structure
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        IrisStructure s = data.load(IrisStructure.class, structure, false);
        if (s == null) {
            sender().sendMessage(C.RED + "No iris structure '" + structure + "' in this pack");
            return;
        }
        StructureAssembler assembler = StructureAssembler.forData(
                data, s, new IrisPosition(0, 64, 0));
        KList<PlacedStructurePiece> pieces = assembler.assemble(new RNG(1234));
        if (pieces == null || pieces.isEmpty()) {
            sender().sendMessage(C.RED + "Structure '" + structure + "' assembled 0 pieces (check startPool '" + s.getStartPool() + "')");
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacedStructurePiece p : pieces) {
            minX = Math.min(minX, p.getMinX());
            minZ = Math.min(minZ, p.getMinZ());
            maxX = Math.max(maxX, p.getMaxX());
            maxZ = Math.max(maxZ, p.getMaxZ());
        }
        sender().sendMessage(C.GREEN + "Structure '" + structure + "': " + C.WHITE + pieces.size() + C.GREEN + " pieces, footprint " + C.WHITE + (maxX - minX + 1) + "x" + (maxZ - minZ + 1) + C.GREEN + " blocks (sample seed 1234)");
    }

    @Director(description = "Assemble and place an iris structure at your location (studio testing)", aliases = {"p"}, origin = DirectorOrigin.PLAYER, sync = true)
    public void place(
            @Param(description = "The dimension whose pack holds the structure", aliases = "dim")
            IrisDimension dimension,
            @Param(description = "The iris structure key to place")
            String structure
    ) {
        IrisData data = dimension.getLoader();
        if (data == null) {
            sender().sendMessage(C.RED + "Could not resolve the pack for dimension " + dimension.getLoadKey());
            return;
        }
        IrisStructure s = data.load(IrisStructure.class, structure, false);
        if (s == null) {
            sender().sendMessage(C.RED + "No iris structure '" + structure + "' in this pack");
            return;
        }
        Location loc = player().getLocation();
        StructureAssembler assembler = StructureAssembler.forData(
                data, s, new IrisPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        RNG rng = new RNG((long) loc.getBlockX() * 341873128712L + loc.getBlockZ());
        KList<PlacedStructurePiece> pieces = assembler.assemble(rng);
        if (pieces == null || pieces.isEmpty()) {
            sender().sendMessage(C.RED + "Structure '" + structure + "' assembled 0 pieces");
            return;
        }
        Map<Block, BlockData> future = new HashMap<>();
        IObjectPlacer placer = CommandObject.createPlacer(player().getWorld(), future);
        for (PlacedStructurePiece p : pieces) {
            IrisObjectPlacement config = new IrisObjectPlacement();
            config.setMode(ObjectPlaceMode.STRUCTURE_PIECE);
            config.setRotation(p.getRotation());
            config.getPlace().add(p.getObject().getLoadKey());
            if (!s.getEdit().isEmpty()) {
                config.setEdit(s.getEdit());
            }
            p.getObject().place(p.getX(), p.getY(), p.getZ(), placer, config, rng, null, null, data);
        }
        sender().sendMessage(C.GREEN + "Placed '" + structure + "' (" + pieces.size() + " pieces) at your location.");
    }

}
