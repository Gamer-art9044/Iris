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

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.annotations.Snippet;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.localization.MessageArgument;
import org.dom4j.Document;
import org.dom4j.Element;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("ALL")
public class IrisCodeWorkspace {
    private final IrisProject project;

    public IrisCodeWorkspace(IrisProject project) {
        this.project = project;
    }

    public void openVSCode(VolmitSender sender) {

        IrisDimension d = IrisData.loadAnyDimension(project.getName(), null);
        J.attemptAsync(() ->
        {
            try {
                if (d == null) {
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_COULD_NOT_LOAD_DIMENSION, MessageArgument.untrusted("value", String.valueOf(project.getName()))));
                    return;
                }

                if (d.getLoader() == null) {
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_COULD_NOT_GET_DIMENSION_LOADER));
                    return;
                }
                File f = d.getLoader().getDataFolder();

                if (!doOpenVSCode(f)) {
                    File ff = new File(d.getLoader().getDataFolder(), d.getLoadKey() + ".code-workspace");
                    IrisLogging.warn("Project missing code-workspace: " + ff.getAbsolutePath() + " Re-creating code workspace.");

                    try {
                        IO.writeAll(ff, createCodeWorkspaceConfig(false));
                    } catch (IOException e1) {
                        IrisLogging.reportError(e1);
                        e1.printStackTrace();
                    }
                    if (!doOpenVSCode(f)) {
                        IrisLogging.warn("Tried creating code workspace but failed a second time. Your project is likely corrupt.");
                    }
                }
            } catch (Throwable e) {
                IrisLogging.reportError(e);
                e.printStackTrace();
            }
        });
    }

    private boolean doOpenVSCode(File f) throws IOException {
        boolean foundWork = false;
        for (File i : Objects.requireNonNull(f.listFiles())) {
            if (i.getName().endsWith(".code-workspace")) {
                foundWork = true;

                if (IrisSettings.get().getStudio().isOpenVSCode()) {
                    if (!GraphicsEnvironment.isHeadless()) {
                        IrisLogging.msg("Opening VSCode. You may see the output from VSCode.");
                        IrisLogging.msg("VSCode output always starts with: '(node:#####) electron'");
                        Thread launcherThread = new Thread(() -> {
                            try {
                                Desktop.getDesktop().open(i);
                            } catch (Throwable e) {
                                IrisLogging.reportError(e);
                            }
                        }, "Iris-VSCode-Launcher");
                        launcherThread.setDaemon(true);
                        launcherThread.start();
                    }
                }

                break;
            }
        }
        return foundWork;
    }

    public File getCodeWorkspaceFile() {
        return new File(project.getPath(), project.getName() + ".code-workspace");
    }

    public boolean updateWorkspace() {
        project.getPath().mkdirs();
        File ws = getCodeWorkspaceFile();

        // Render before touching the file: a config-generation failure has nothing to do with
        // the existing workspace file, and deleting it before a rethrowing rebuild silently
        // destroyed the author's workspace on every boot.
        String rendered;
        try {
            rendered = createCodeWorkspaceConfig().toString(4);
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            IrisLogging.warn("Could not generate the code workspace config for " + ws.getAbsolutePath() + "; leaving the existing workspace file untouched.");
            return false;
        }

        try {
            writeIfChanged(ws, rendered);
            return true;
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            IrisLogging.warn("Project invalid: " + ws.getAbsolutePath() + " Re-creating. You may loose some vs-code workspace settings! But not your actual project!");
            ws.delete();
            try {
                IO.writeAll(ws, rendered);
            } catch (Throwable e1) {
                IrisLogging.reportError(e1);
                e1.printStackTrace();
            }
        }

        return false;
    }

    public JSONObject createCodeWorkspaceConfig() {
        return createCodeWorkspaceConfig(true);
    }

    private static void writeIfChanged(File target, String rendered) throws IOException {
        if (target.isFile() && (rendered + "\n").equals(IO.readAll(target))) {
            return;
        }
        IO.writeAll(target, rendered);
    }

    private JSONObject createCodeWorkspaceConfig(boolean includeSchemas) {
        JSONObject ws = new JSONObject();
        JSONArray folders = new JSONArray();
        JSONObject folder = new JSONObject();
        folder.put("path", ".");
        folders.put(folder);
        ws.put("folders", folders);
        JSONObject settings = new JSONObject();
        settings.put("workbench.colorTheme", "Monokai");
        settings.put("workbench.preferredDarkColorTheme", "Solarized Dark");
        settings.put("workbench.tips.enabled", false);
        settings.put("workbench.tree.indent", 24);
        settings.put("files.autoSave", "onFocusChange");
        JSONObject jc = new JSONObject();
        jc.put("editor.autoIndent", "brackets");
        jc.put("editor.acceptSuggestionOnEnter", "smart");
        jc.put("editor.cursorSmoothCaretAnimation", true);
        jc.put("editor.dragAndDrop", false);
        jc.put("files.trimTrailingWhitespace", true);
        jc.put("diffEditor.ignoreTrimWhitespace", true);
        jc.put("files.trimFinalNewlines", true);
        jc.put("editor.suggest.showKeywords", false);
        jc.put("editor.suggest.showSnippets", false);
        jc.put("editor.suggest.showWords", false);
        JSONObject st = new JSONObject();
        st.put("strings", true);
        jc.put("editor.quickSuggestions", st);
        jc.put("editor.suggest.insertMode", "replace");
        settings.put("[json]", jc);
        settings.put("json.maxItemsComputed", 30000);
        JSONArray schemas = new JSONArray();
        List<JSONObject> schemaEntries = new ArrayList<>();
        IrisData dm = null;
        if (includeSchemas) {
            dm = IrisData.get(project.getPath());
            for (ResourceLoader<?> r : dm.getLoaders().v()) {
                if (r.supportsSchemas()) {
                    schemaEntries.add(r.buildSchema());
                }
            }

            for (Class<?> i : sortedSnippets(dm.resolveSnippets())) {
                try {
                    String snipType = i.getDeclaredAnnotation(Snippet.class).value();
                    JSONObject o = new JSONObject();
                    KList<String> fm = new KList<>();

                    for (int g = 1; g < 8; g++) {
                        fm.add("/snippet/" + snipType + Form.repeat("/*", g) + ".json");
                    }

                    o.put("fileMatch", new JSONArray(fm.toArray()));
                    o.put("url", "./.iris/schema/snippet/" + snipType + "-schema.json");
                    schemaEntries.add(o);
                    IrisData snippetData = dm;
                    File a = new File(snippetData.getDataFolder(), ".iris/schema/snippet/" + snipType + "-schema.json");
                    J.attemptAsync(() -> {
                        try {
                            IO.writeAll(a, new SchemaBuilder(i, snippetData).construct().toString(4));
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                    });
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }

            schemaEntries.sort(Comparator.comparing(entry -> entry.getString("url")));
            for (JSONObject entry : schemaEntries) {
                schemas.put(entry);
            }
        }

        settings.put("json.schemas", schemas);
        ws.put("settings", settings);

        if (!includeSchemas) {
            return ws;
        }

        File schemasFile = new File(project.getPath(), ".idea" + File.separator + "jsonSchemas.xml");
        Document doc = IO.read(schemasFile);
        Element mappings = (Element) doc.selectSingleNode("//component[@name='JsonSchemaMappingsProjectConfiguration']");
        if (mappings == null) {
            mappings = doc.getRootElement()
                    .addElement("component")
                    .addAttribute("name", "JsonSchemaMappingsProjectConfiguration");
        }

        Element state = (Element) mappings.selectSingleNode("state");
        if (state == null) state = mappings.addElement("state");

        Element map = (Element) state.selectSingleNode("map");
        if (map == null) map = state.addElement("map");
        var schemaMap = new KMap<String, String>();
        schemas.forEach(element -> {
            if (!(element instanceof JSONObject obj))
                return;

            String url = obj.getString("url");
            String dir = obj.getJSONArray("fileMatch").getString(0);
            schemaMap.put(url, dir.substring(1, dir.indexOf("/*")));
        });

        map.selectNodes("entry/value/SchemaInfo/option[@name='relativePathToSchema']")
                .stream()
                .map(node -> node.valueOf("@value"))
                .forEach(schemaMap::remove);

        var ideaSchemas = map;
        schemaMap.forEach((url, dir) -> {
            var genName = UUID.randomUUID().toString();

            var info = ideaSchemas.addElement("entry")
                    .addAttribute("key", genName)
                    .addElement("value")
                    .addElement("SchemaInfo");
            info.addElement("option")
                    .addAttribute("name", "generatedName")
                    .addAttribute("value", genName);
            info.addElement("option")
                    .addAttribute("name", "name")
                    .addAttribute("value", dir);
            info.addElement("option")
                    .addAttribute("name", "relativePathToSchema")
                    .addAttribute("value", url);


            var item = info.addElement("option")
                    .addAttribute("name", "patterns")
                    .addElement("list")
                    .addElement("Item");
            item.addElement("option")
                    .addAttribute("name", "directory")
                    .addAttribute("value", "true");
            item.addElement("option")
                    .addAttribute("name", "path")
                    .addAttribute("value", dir);
            item.addElement("option")
                    .addAttribute("name", "mappingKind")
                    .addAttribute("value", "Directory");
        });
        if (!schemaMap.isEmpty()) {
            IO.write(schemasFile, doc);
        }
        return ws;
    }

    private static List<Class<?>> sortedSnippets(Set<Class<?>> snippets) {
        List<Class<?>> sorted = new ArrayList<>(snippets);
        sorted.sort(Comparator.comparing(Class::getName));
        return sorted;
    }
}
