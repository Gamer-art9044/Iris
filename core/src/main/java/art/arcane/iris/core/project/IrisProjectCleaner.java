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

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("ALL")
public final class IrisProjectCleaner {
    private IrisProjectCleaner() {
    }

    public static int clean(VolmitSender s, File clean) {
        int c = 0;
        if (clean.isDirectory()) {
            for (File i : clean.listFiles()) {
                c += clean(s, i);
            }
        } else if (clean.getName().endsWith(".json")) {
            try {
                clean(clean);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                IrisLogging.error("Failed to beautify " + clean.getAbsolutePath() + " You may have errors in your json!");
            }

            c++;
        }

        return c;
    }

    public static void clean(File clean) throws IOException {
        JSONObject obj = new JSONObject(IO.readAll(clean));
        fixBlocks(obj, clean);

        IO.writeAll(clean, obj.toString(4));
    }

    public static void fixBlocks(JSONObject obj, File f) {
        for (String i : obj.keySet()) {
            Object o = obj.get(i);

            if (i.equals("block") && o instanceof String && !o.toString().trim().isEmpty() && !o.toString().contains(":")) {
                obj.put(i, "minecraft:" + o);
                IrisLogging.debug("Updated Block Key: " + o + " to " + obj.getString(i) + " in " + f.getPath());
            }

            if (o instanceof JSONObject) {
                fixBlocks((JSONObject) o, f);
            } else if (o instanceof JSONArray) {
                fixBlocks((JSONArray) o, f);
            }
        }
    }

    public static void fixBlocks(JSONArray obj, File f) {
        for (int i = 0; i < obj.length(); i++) {
            Object o = obj.get(i);

            if (o instanceof JSONObject) {
                fixBlocks((JSONObject) o, f);
            } else if (o instanceof JSONArray) {
                fixBlocks((JSONArray) o, f);
            }
        }
    }

    static void fixBlocks(JSONObject obj) {
        for (String i : obj.keySet()) {
            Object o = obj.get(i);

            if (i.equals("block") && o instanceof String && !o.toString().trim().isEmpty() && !o.toString().contains(":")) {
                obj.put(i, "minecraft:" + o);
            }

            if (o instanceof JSONObject) {
                fixBlocks((JSONObject) o);
            } else if (o instanceof JSONArray) {
                fixBlocks((JSONArray) o);
            }
        }
    }

    static void fixBlocks(JSONArray obj) {
        for (int i = 0; i < obj.length(); i++) {
            Object o = obj.get(i);

            if (o instanceof JSONObject) {
                fixBlocks((JSONObject) o);
            } else if (o instanceof JSONArray) {
                fixBlocks((JSONArray) o);
            }
        }
    }

    public static void files(File clean, KList<File> files) {
        if (clean.isDirectory()) {
            for (File i : clean.listFiles()) {
                files(i, files);
            }
        } else if (clean.getName().endsWith(".json")) {
            try {
                files.add(clean);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
    }

    public static void filesObjects(File clean, KList<File> files) {
        if (clean.isDirectory()) {
            for (File i : clean.listFiles()) {
                filesObjects(i, files);
            }
        } else if (clean.getName().endsWith(".iob")) {
            try {
                files.add(clean);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }
    }
}
