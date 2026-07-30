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

package art.arcane.iris.core.project;

import com.google.gson.Gson;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.pack.StructurePackageClosure;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBlockData;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisEntity;
import art.arcane.iris.engine.object.IrisGenerator;
import art.arcane.iris.engine.object.IrisLootTable;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisSpawner;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.volmlib.util.scheduling.O;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("ALL")
public class IrisPackageCompiler {
    private final IrisProject project;

    public IrisPackageCompiler(IrisProject project) {
        this.project = project;
    }

    public File compilePackage(VolmitSender sender, boolean obfuscate, boolean minify) {
        String dimm = project.getName();
        IrisData dm = IrisData.get(project.getPath());
        IrisDimension dimension = dm.getDimensionLoader().load(dimm);
        File folder = new File(IrisPlatforms.get().dataFolder(), "exports/" + dimension.getLoadKey());
        IO.delete(folder);
        if (folder.exists()) {
            throw new IllegalStateException("Failed to clear structure package staging folder " + folder.getAbsolutePath());
        }
        if (!folder.mkdirs() && !folder.isDirectory()) {
            throw new IllegalStateException("Failed to create structure package staging folder " + folder.getAbsolutePath());
        }
        IrisLogging.info("Packaging Dimension " + dimension.getName() + " " + (obfuscate ? "(Obfuscated)" : ""));
        KSet<IrisRegion> regions = new KSet<>();
        KSet<IrisBiome> biomes = new KSet<>();
        KSet<IrisEntity> entities = new KSet<>();
        KSet<IrisSpawner> spawners = new KSet<>();
        KSet<IrisGenerator> generators = new KSet<>();
        KSet<IrisLootTable> loot = new KSet<>();
        KSet<IrisBlockData> blocks = new KSet<>();

        for (String i : dm.getBlockLoader().getPossibleKeys()) {
            blocks.add(dm.getBlockLoader().load(i));
        }

        dimension.getRegions().forEach((i) -> regions.add(dm.getRegionLoader().load(i)));
        dimension.getLoot().getTables().forEach((i) -> loot.add(dm.getLootLoader().load(i)));
        regions.forEach((i) -> biomes.addAll(i.getAllBiomes(() -> dm)));
        regions.forEach((r) -> r.getLoot().getTables().forEach((i) -> loot.add(dm.getLootLoader().load(i))));
        regions.forEach((r) -> r.getEntitySpawners().forEach((sp) -> spawners.add(dm.getSpawnerLoader().load(sp))));
        dimension.getEntitySpawners().forEach((sp) -> spawners.add(dm.getSpawnerLoader().load(sp)));
        biomes.forEach((i) -> i.getGenerators().forEach((j) -> generators.add(j.getCachedGenerator(() -> dm))));
        biomes.forEach((r) -> r.getLoot().getTables().forEach((i) -> loot.add(dm.getLootLoader().load(i))));
        biomes.forEach((r) -> r.getEntitySpawners().forEach((sp) -> spawners.add(dm.getSpawnerLoader().load(sp))));
        collectSpawnerEntityKeys(spawners).forEach((i) -> entities.add(dm.getEntityLoader().load(i)));
        Set<String> structureKeys = new LinkedHashSet<>();
        collectStructureKeys(structureKeys, dimension.getStructures());
        regions.forEach((region) -> collectStructureKeys(structureKeys, region.getStructures()));
        biomes.forEach((biome) -> collectStructureKeys(structureKeys, biome.getStructures()));
        KMap<String, String> renameObjects = new KMap<>();
        String a;
        StringBuilder b = new StringBuilder();
        StringBuilder c = new StringBuilder();
        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_SERIALIZING_OBJECTS));

        for (IrisBiome i : biomes) {
            for (IrisObjectPlacement j : i.getObjects()) {
                b.append(j.hashCode());
                KList<String> newNames = new KList<>();

                for (String k : j.getPlace()) {
                    if (renameObjects.containsKey(k)) {
                        newNames.add(renameObjects.get(k));
                        continue;
                    }

                    String name = !obfuscate ? k : UUID.randomUUID().toString().replaceAll("-", "");
                    b.append(name);
                    newNames.add(name);
                    renameObjects.put(k, name);
                }

                j.setPlace(newNames);
            }
        }

        KMap<String, KList<String>> lookupObjects = renameObjects.flip();
        StringBuilder gb = new StringBuilder();
        ChronoLatch cl = new ChronoLatch(1000);
        O<Integer> ggg = new O<>();
        ggg.set(0);
        biomes.forEach((i) -> i.getObjects().forEach((j) -> j.getPlace().forEach((k) ->
        {
            try {
                File f = dm.getObjectLoader().findFile(lookupObjects.get(k).get(0));
                IO.copyFile(f, new File(folder, "objects/" + k + ".iob"));
                gb.append(IO.hash(f));
                ggg.set(ggg.get() + 1);

                if (cl.flip()) {
                    int g = ggg.get();
                    ggg.set(0);
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_WROTE_ANOTHER_OBJECTS, MessageArgument.untrusted("g", String.valueOf(g))));
                }
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        })));

        b.append(IO.hash(gb.toString()));
        c.append(IO.hash(b.toString()));
        b = new StringBuilder();

        IrisLogging.info("Writing Dimensional Scaffold");

        try {
            StructurePackageClosure structureClosure = StructurePackageClosure.collect(project.getPath(), structureKeys);
            if (!structureClosure.isValid()) {
                throw new IOException("Structure package closure is invalid: " + String.join("; ", structureClosure.errors()));
            }
            b.append(structureClosure.writeTo(folder, minify));
            a = new JSONObject(new Gson().toJson(dimension)).toString(minify ? 0 : 4);
            IO.writeAll(new File(folder, "dimensions/" + dimension.getLoadKey() + ".json"), a);
            b.append(IO.hash(a));

            for (IrisGenerator i : generators) {
                a = new JSONObject(new Gson().toJson(i)).toString(minify ? 0 : 4);
                IO.writeAll(new File(folder, "generators/" + i.getLoadKey() + ".json"), a);
                b.append(IO.hash(a));
            }

            c.append(IO.hash(b.toString()));
            b = new StringBuilder();

            for (IrisRegion i : regions) {
                a = new JSONObject(new Gson().toJson(i)).toString(minify ? 0 : 4);
                IO.writeAll(new File(folder, "regions/" + i.getLoadKey() + ".json"), a);
                b.append(IO.hash(a));
            }

            for (IrisBlockData i : blocks) {
                a = new JSONObject(new Gson().toJson(i)).toString(minify ? 0 : 4);
                IO.writeAll(new File(folder, "blocks/" + i.getLoadKey() + ".json"), a);
                b.append(IO.hash(a));
            }

            for (IrisBiome i : biomes) {
                a = new JSONObject(new Gson().toJson(i)).toString(minify ? 0 : 4);
                IO.writeAll(new File(folder, "biomes/" + i.getLoadKey() + ".json"), a);
                b.append(IO.hash(a));
            }

            for (IrisEntity i : entities) {
                a = new JSONObject(new Gson().toJson(i)).toString(minify ? 0 : 4);
                IO.writeAll(new File(folder, "entities/" + i.getLoadKey() + ".json"), a);
                b.append(IO.hash(a));
            }

            for (IrisLootTable i : loot) {
                a = new JSONObject(new Gson().toJson(i)).toString(minify ? 0 : 4);
                IO.writeAll(new File(folder, "loot/" + i.getLoadKey() + ".json"), a);
                b.append(IO.hash(a));
            }

            c.append(IO.hash(b.toString()));
            String finalHash = IO.hash(c.toString());
            JSONObject meta = new JSONObject();
            meta.put("hash", finalHash);
            meta.put("time", M.ms());
            meta.put("version", dimension.getVersion());
            IO.writeAll(new File(folder, "package.json"), meta.toString(minify ? 0 : 4));
            File p = new File(IrisPlatforms.get().dataFolder(), "exports/" + dimension.getLoadKey() + ".iris");
            IrisLogging.info("Compressing Package");
            ZipUtil.pack(folder, p, 9);
            IO.delete(folder);

            sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_PACKAGE_COMPILED));
            return p;
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            e.printStackTrace();
        }
        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_FAILED));
        return null;
    }

    static KSet<String> collectSpawnerEntityKeys(KSet<IrisSpawner> spawners) {
        KSet<String> entityKeys = new KSet<>();
        for (IrisSpawner spawner : spawners) {
            spawner.getSpawns().forEach((spawn) -> entityKeys.add(spawn.getEntity()));
            spawner.getInitialSpawns().forEach((spawn) -> entityKeys.add(spawn.getEntity()));
        }
        return entityKeys;
    }

    private static void collectStructureKeys(Set<String> keys, KList<IrisStructurePlacement> placements) {
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
}
