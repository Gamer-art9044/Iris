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
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.core.pack.StructurePackageClosure;
import art.arcane.iris.core.runtime.StudioOpenCoordinator;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBlockData;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisEntity;
import art.arcane.iris.engine.object.IrisGenerator;
import art.arcane.iris.engine.object.IrisLootTable;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisSpawner;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.engine.object.annotations.Snippet;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.volmlib.util.exceptions.IrisException;
import art.arcane.iris.util.common.format.C;
import art.arcane.volmlib.util.format.Form;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.math.M;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.volmlib.util.scheduling.ChronoLatch;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.O;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;
import art.arcane.iris.util.common.scheduling.jobs.Job;
import art.arcane.iris.util.common.scheduling.jobs.JobCollection;
import art.arcane.iris.util.common.scheduling.jobs.ParallelQueueJob;
import lombok.Data;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.dom4j.Document;
import org.dom4j.Element;
import org.zeroturnaround.zip.ZipUtil;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import art.arcane.iris.core.localization.BukkitRuntimeMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeProgressMessages;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.volmlib.util.localization.MessageArgument;
@SuppressWarnings("ALL")
@Data
public class IrisProject {
    private File path;
    private String name;
    private PlatformChunkGenerator activeProvider;

    public IrisProject(File path) {
        this.path = path;
        this.name = path.getName();
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

    public boolean isOpen() {
        return activeProvider != null;
    }

    public KList<File> collectFiles(File f, String fileExtension) {
        KList<File> l = new KList<>();

        if (f.isDirectory()) {
            for (File i : f.listFiles()) {
                l.addAll(collectFiles(i, fileExtension));
            }
        } else if (f.getName().endsWith("." + fileExtension)) {
            l.add(f);
        }

        return l;
    }

    public KList<File> collectFiles(String json) {
        return collectFiles(path, json);
    }

    public void open(VolmitSender sender) throws IrisException {
        open(sender, 1337, (w) ->
        {
        });
    }

    public void openVSCode(VolmitSender sender) {

        IrisDimension d = IrisData.loadAnyDimension(getName(), null);
        J.attemptAsync(() ->
        {
            try {
                if (d == null) {
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_COULD_NOT_LOAD_DIMENSION, MessageArgument.untrusted("value", String.valueOf(getName()))));
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

    public CompletableFuture<StudioOpenCoordinator.StudioOpenResult> open(VolmitSender sender, long seed, Consumer<World> onDone) throws IrisException {
        if (isOpen()) {
            return close().thenCompose(ignored -> openInternal(sender, seed, onDone));
        }

        return openInternal(sender, seed, onDone);
    }

    private CompletableFuture<StudioOpenCoordinator.StudioOpenResult> openInternal(VolmitSender sender, long seed, Consumer<World> onDone) {
        AtomicReference<String> stage = new AtomicReference<>("Queued");
        AtomicReference<Double> progress = new AtomicReference<>(0.01D);
        AtomicBoolean complete = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        CompletableFuture<StudioOpenCoordinator.StudioOpenResult> future = StudioOpenCoordinator.get().open(
                StudioOpenCoordinator.StudioOpenRequest.studioProject(
                        this,
                        sender,
                        seed,
                        update -> {
                            if (update.stage() != null && !update.stage().isBlank()) {
                                stage.set(update.stage());
                            }
                            progress.set(Math.max(0D, Math.min(0.99D, update.progress())));
                        },
                        onDone
                )
        );
        startStudioOpenReporter(sender, stage, progress, complete, failed);
        future.whenComplete((result, throwable) -> {
            World maintenanceWorld = null;
            boolean maintenanceActive = false;
            try {
                if (throwable != null) {
                    failed.set(true);
                    Throwable error = throwable instanceof java.util.concurrent.CompletionException completionException && completionException.getCause() != null
                            ? completionException.getCause()
                            : throwable;
                    IrisLogging.reportError("Studio open failed for project \"" + getName() + "\".", error);
                    sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_STUDIO_OPEN_FAILED, MessageArgument.untrusted("error", String.valueOf(error.getMessage()))));
                    return;
                }

                if (sender.isPlayer() && sender.player() != null) {
                    J.runEntity(sender.player(), () -> sender.player().setGameMode(GameMode.CREATIVE));
                }
                activeProvider = IrisToolbelt.access(result.world());
                maintenanceWorld = result.world();
                if (maintenanceWorld != null) {
                    IrisToolbelt.beginWorldMaintenance(maintenanceWorld, "studio-open");
                    maintenanceActive = true;
                }
            } finally {
                if (maintenanceActive && maintenanceWorld != null) {
                    World worldToRelease = maintenanceWorld;
                    J.a(() -> IrisToolbelt.endWorldMaintenance(worldToRelease, "studio-open"), 300);
                }
                complete.set(true);
            }
        });
        return future;
    }

    private static final int STUDIO_PROGRESS_BAR_WIDTH = 44;

    private void startStudioOpenReporter(VolmitSender sender, AtomicReference<String> stage, AtomicReference<Double> progress, AtomicBoolean complete, AtomicBoolean failed) {
        AtomicLong nextConsoleUpdate = new AtomicLong(0L);
        AtomicLong startMs = new AtomicLong(System.currentTimeMillis());
        AtomicInteger taskId = new AtomicInteger(-1);
        org.bukkit.boss.BossBar bossBar;

        if (sender.isPlayer() && sender.player() != null) {
            bossBar = Bukkit.createBossBar(
                    IrisLanguage.text(RuntimeProgressMessages.STUDIO_OPENING),
                    org.bukkit.boss.BarColor.BLUE,
                    org.bukkit.boss.BarStyle.SEGMENTED_20
            );
            bossBar.setProgress(0.0D);
            bossBar.addPlayer(sender.player());
            bossBar.setVisible(true);
        } else {
            bossBar = null;
        }

        int scheduledTaskId = J.ar(() -> {
            double currentProgress = Math.max(0D, Math.min(0.99D, progress.get()));
            String currentStage = describeStage(stage.get());
            int percent = (int) Math.round(currentProgress * 100.0D);
            long elapsed = System.currentTimeMillis() - startMs.get();

            if (complete.get()) {
                J.car(taskId.get());

                if (failed.get()) {
                    if (bossBar != null) {
                        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, currentProgress)));
                        bossBar.setColor(org.bukkit.boss.BarColor.RED);
                        bossBar.setTitle(IrisLanguage.text(
                                RuntimeProgressMessages.STUDIO_FAILED_PROGRESS,
                                MessageArgument.trusted("percent", percent)
                        ));
                        J.a(() -> { bossBar.removeAll(); bossBar.setVisible(false); }, 60);
                    }
                    if (sender.isPlayer()) {
                        sender.sendAction(IrisLanguage.text(
                                RuntimeProgressMessages.STUDIO_ACTION_FAILED,
                                MessageArgument.trusted("bar", buildStudioProgressBar(currentProgress)),
                                MessageArgument.trusted("stage", currentStage)
                        ));
                    } else {
                        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_STUDIO_OPEN_FAILED_2));
                    }
                } else {
                    if (bossBar != null) {
                        bossBar.setProgress(1.0D);
                        bossBar.setColor(org.bukkit.boss.BarColor.GREEN);
                        bossBar.setTitle(IrisLanguage.text(RuntimeProgressMessages.STUDIO_READY_PROGRESS));
                        J.a(() -> { bossBar.removeAll(); bossBar.setVisible(false); }, 60);
                    }
                    if (sender.isPlayer()) {
                        sender.sendAction(IrisLanguage.text(
                                RuntimeProgressMessages.STUDIO_ACTION_READY,
                                MessageArgument.trusted("bar", buildStudioProgressBar(1.0D)),
                                MessageArgument.trusted("elapsed", Form.duration(elapsed, 1))
                        ));
                    } else {
                        sender.sendMessage(IrisLanguage.text(BukkitRuntimeMessages.IRIS_PROJECT_STUDIO_READY, MessageArgument.untrusted("value", String.valueOf(Form.duration(elapsed, 1)))));
                    }
                }
                return;
            }

            if (sender.isPlayer() && sender.player() != null) {
                if (bossBar != null) {
                    bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, currentProgress)));
                    bossBar.setTitle(IrisLanguage.text(
                            RuntimeProgressMessages.STUDIO_OPENING_PROGRESS,
                            MessageArgument.trusted("percent", percent)
                    ));
                }

                sender.sendAction(IrisLanguage.text(
                        RuntimeProgressMessages.STUDIO_ACTION_PROGRESS,
                        MessageArgument.trusted("bar", buildStudioProgressBar(currentProgress)),
                        MessageArgument.trusted("percent", percent),
                        MessageArgument.trusted("stage", currentStage),
                        MessageArgument.trusted("elapsed", Form.duration(elapsed, 0))
                ));
            } else {
                long now = System.currentTimeMillis();
                long nextUpdate = nextConsoleUpdate.get();
                if (now >= nextUpdate) {
                    String bar = buildStudioConsoleBar(currentProgress);
                    sender.sendMessage(IrisLanguage.text(
                            RuntimeProgressMessages.STUDIO_CONSOLE_PROGRESS,
                            MessageArgument.trusted("bar", bar),
                            MessageArgument.trusted("percent", percent),
                            MessageArgument.trusted("stage", currentStage),
                            MessageArgument.trusted("elapsed", Form.duration(elapsed, 0))
                    ));
                    nextConsoleUpdate.set(now + 1500L);
                }
            }
        }, 3);

        taskId.set(scheduledTaskId);
    }

    private static String buildStudioProgressBar(double progress) {
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * STUDIO_PROGRESS_BAR_WIDTH);
        StringBuilder bar = new StringBuilder(STUDIO_PROGRESS_BAR_WIDTH * 3 + 4);
        bar.append(C.DARK_GRAY).append("[");
        for (int i = 0; i < STUDIO_PROGRESS_BAR_WIDTH; i++) {
            bar.append(i < filled ? C.GREEN : C.DARK_GRAY).append("|");
        }
        bar.append(C.DARK_GRAY).append("]");
        return bar.toString();
    }

    private static String buildStudioConsoleBar(double progress) {
        int width = 20;
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * width);
        StringBuilder bar = new StringBuilder();
        bar.append("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? "#" : "-");
        }
        bar.append("]");
        return bar.toString();
    }

    private static String describeStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_INITIALIZING);
        }
        return switch (stage) {
            case "Queued" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_QUEUED);
            case "resolve_dimension" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_RESOLVE_DIMENSION);
            case "prepare_world_pack" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_PREPARE_WORLD_PACK);
            case "install_datapacks" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_INSTALL_DATAPACKS);
            case "create_world" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_CREATE_WORLD);
            case "apply_world_rules" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_APPLY_WORLD_RULES);
            case "prepare_generator" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_PREPARE_GENERATOR);
            case "request_entry_chunk" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_REQUEST_ENTRY_CHUNK);
            case "resolve_safe_entry" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_RESOLVE_SAFE_ENTRY);
            case "teleport_player" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_TELEPORT_PLAYER);
            case "finalize_open" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_FINALIZE_OPEN);
            case "cleanup" -> IrisLanguage.text(RuntimeProgressMessages.STUDIO_STAGE_CLEANUP);
            default -> Form.capitalizeWords(stage.replace('_', ' '));
        };
    }

    public CompletableFuture<StudioOpenCoordinator.StudioCloseResult> close() {
        if (activeProvider == null) {
            return CompletableFuture.completedFuture(new StudioOpenCoordinator.StudioCloseResult(null, true, true, false, null));
        }

        return StudioOpenCoordinator.get().closeProject(this);
    }

    public File getCodeWorkspaceFile() {
        return new File(path, getName() + ".code-workspace");
    }

    public boolean updateWorkspace() {
        getPath().mkdirs();
        File ws = getCodeWorkspaceFile();

        try {
            PrecisionStopwatch p = PrecisionStopwatch.start();
            JSONObject j = createCodeWorkspaceConfig();
            IO.writeAll(ws, j.toString(4));
            p.end();
            return true;
        } catch (Throwable e) {
            IrisLogging.reportError(e);
            IrisLogging.warn("Project invalid: " + ws.getAbsolutePath() + " Re-creating. You may loose some vs-code workspace settings! But not your actual project!");
            ws.delete();
            try {
                IO.writeAll(ws, createCodeWorkspaceConfig());
            } catch (IOException e1) {
                IrisLogging.reportError(e1);
                e1.printStackTrace();
            }
        }

        return false;
    }

    public JSONObject createCodeWorkspaceConfig() {
        return createCodeWorkspaceConfig(true);
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
        IrisData dm = null;
        if (includeSchemas) {
            dm = IrisData.get(getPath());
            for (ResourceLoader<?> r : dm.getLoaders().v()) {
                if (r.supportsSchemas()) {
                    schemas.put(r.buildSchema());
                }
            }

            for (Class<?> i : dm.resolveSnippets()) {
                try {
                    String snipType = i.getDeclaredAnnotation(Snippet.class).value();
                    JSONObject o = new JSONObject();
                    KList<String> fm = new KList<>();

                    for (int g = 1; g < 8; g++) {
                        fm.add("/snippet/" + snipType + Form.repeat("/*", g) + ".json");
                    }

                    o.put("fileMatch", new JSONArray(fm.toArray()));
                    o.put("url", "./.iris/schema/snippet/" + snipType + "-schema.json");
                    schemas.put(o);
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
        }

        settings.put("json.schemas", schemas);
        ws.put("settings", settings);

        if (!includeSchemas) {
            return ws;
        }

        File schemasFile = new File(path, ".idea" + File.separator + "jsonSchemas.xml");
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

    public File compilePackage(VolmitSender sender, boolean obfuscate, boolean minify) {
        String dimm = getName();
        IrisData dm = IrisData.get(path);
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
            StructurePackageClosure structureClosure = StructurePackageClosure.collect(path, structureKeys);
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

    public void compile(VolmitSender sender) {
        IrisData data = IrisData.get(getPath());
        KList<Job> jobs = new KList<>();
        KList<File> files = new KList<>();
        KList<File> objects = new KList<>();
        files(getPath(), files);
        filesObjects(getPath(), objects);

        jobs.add(new ParallelQueueJob<File>() {
            @Override
            public void execute(File f) {
                try {
                    IrisObject o = new IrisObject(0, 0, 0);
                    o.read(f);

                    if (o.getBlocks().isEmpty()) {
                        sender.sendComponent(Component.text(IrisLanguage.plain(
                                        RuntimeUiMessages.COMPILE_IOB_EMPTY,
                                        MessageArgument.untrusted("file", f.getName())
                                ), NamedTextColor.RED)
                                .hoverEvent(HoverEvent.showText(Component.text(IrisLanguage.plain(
                                        RuntimeUiMessages.COMPILE_IOB_EMPTY_HOVER,
                                        MessageArgument.untrusted("path", f.getPath())
                                ), NamedTextColor.YELLOW))));
                    }

                    if (o.getW() == 0 || o.getH() == 0 || o.getD() == 0) {
                        sender.sendComponent(Component.text(IrisLanguage.plain(
                                        RuntimeUiMessages.COMPILE_IOB_NOT_3D,
                                        MessageArgument.untrusted("file", f.getName())
                                ), NamedTextColor.RED)
                                .hoverEvent(HoverEvent.showText(Component.text(IrisLanguage.plain(
                                        RuntimeUiMessages.COMPILE_IOB_NOT_3D_HOVER,
                                        MessageArgument.untrusted("path", f.getPath())
                                ), NamedTextColor.YELLOW))));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public String getName() {
                return "IOB";
            }
        }.queue(objects));

        jobs.add(new ParallelQueueJob<File>() {
            @Override
            public void execute(File f) {
                try {
                    JSONObject p = new JSONObject(IO.readAll(f));
                    fixBlocks(p);
                    scanForErrors(data, f, p, sender);
                    IO.writeAll(f, p.toString(4));

                } catch (Throwable e) {
                    sender.sendComponent(Component.text(IrisLanguage.plain(
                                    RuntimeUiMessages.COMPILE_JSON_ERROR,
                                    MessageArgument.untrusted("file", f.getName())
                            ), NamedTextColor.RED)
                            .hoverEvent(HoverEvent.showText(Component.text(IrisLanguage.plain(
                                    RuntimeUiMessages.COMPILE_JSON_ERROR_HOVER,
                                    MessageArgument.untrusted("path", f.getPath()),
                                    MessageArgument.untrusted("error", String.valueOf(e.getMessage()))
                            ), NamedTextColor.YELLOW))));
                }
            }

            @Override
            public String getName() {
                return "JSON";
            }
        }.queue(files));

        new JobCollection(IrisLanguage.text(RuntimeUiMessages.JOB_COMPILE), jobs).execute(sender);
    }

    private void scanForErrors(IrisData data, File f, JSONObject p, VolmitSender sender) {
        String key = data.toLoadKey(f);
        ResourceLoader<?> loader = data.getTypedLoaderFor(f);

        if (loader == null) {
            sender.sendMessage(IrisLanguage.text(
                    RuntimeUiMessages.COMPILE_LOADER_NOT_FOUND,
                    MessageArgument.untrusted("path", f.getPath())
            ));
            return;
        }

        IrisRegistrant load = loader.load(key);
        compare(load.getClass(), p, sender, new KList<>());
        load.scanForErrors(p, sender);
    }

    public void compare(Class<?> c, JSONObject j, VolmitSender sender, KList<String> path) {
        try {
            Object o = c.getClass().getConstructor().newInstance();
        } catch (Throwable e) {

        }
    }

    public void files(File clean, KList<File> files) {
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

    public void filesObjects(File clean, KList<File> files) {
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

    private void fixBlocks(JSONObject obj) {
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

    private void fixBlocks(JSONArray obj) {
        for (int i = 0; i < obj.length(); i++) {
            Object o = obj.get(i);

            if (o instanceof JSONObject) {
                fixBlocks((JSONObject) o);
            } else if (o instanceof JSONArray) {
                fixBlocks((JSONArray) o);
            }
        }
    }
}
