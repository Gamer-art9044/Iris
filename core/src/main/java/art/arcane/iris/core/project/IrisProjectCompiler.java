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

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.util.common.plugin.VolmitSender;
import art.arcane.iris.util.common.scheduling.jobs.Job;
import art.arcane.iris.util.common.scheduling.jobs.JobCollection;
import art.arcane.iris.util.common.scheduling.jobs.ParallelQueueJob;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.localization.MessageArgument;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("ALL")
public class IrisProjectCompiler {
    private final IrisProject project;

    public IrisProjectCompiler(IrisProject project) {
        this.project = project;
    }

    public void compile(VolmitSender sender) {
        IrisData data = IrisData.get(project.getPath());
        KList<Job> jobs = new KList<>();
        KList<File> files = new KList<>();
        KList<File> objects = new KList<>();
        IrisProjectCleaner.files(project.getPath(), files);
        IrisProjectCleaner.filesObjects(project.getPath(), objects);

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
                    IrisProjectCleaner.fixBlocks(p);
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
}
