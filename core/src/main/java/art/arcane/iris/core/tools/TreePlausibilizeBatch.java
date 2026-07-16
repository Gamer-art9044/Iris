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

package art.arcane.iris.core.tools;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.spi.IrisLogging;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class TreePlausibilizeBatch {
    private TreePlausibilizeBatch() {
    }

    public record Target(String key, File file) {
    }

    public static List<Target> resolve(String target, IrisData data) {
        List<Target> out = new ArrayList<>();
        if (target == null || target.isEmpty()) {
            return out;
        }

        File direct = new File(target);
        if (direct.isFile() && target.toLowerCase(Locale.ROOT).endsWith(".iob")) {
            out.add(new Target(direct.getName().replaceAll("\\.iob$", ""), direct));
            return out;
        }
        if (direct.isDirectory()) {
            walkIob(direct, direct, out);
            return out;
        }

        if (data != null) {
            ResourceLoader<IrisObject> loader = data.getObjectLoader();
            if (!target.endsWith("/") && loader.findFile(target) != null) {
                out.add(new Target(target, null));
                return out;
            }
            String prefix = target.endsWith("/") ? target : target + "/";
            for (String k : loader.getPossibleKeys()) {
                if (k.startsWith(prefix)) {
                    out.add(new Target(k, null));
                }
            }
        }
        return out;
    }

    public static void walkIob(File root, File keyRoot, List<Target> out) {
        File[] kids = root.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                walkIob(f, keyRoot, out);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".iob")) {
                String rel = keyRoot.toPath().relativize(f.toPath()).toString()
                        .replace(File.separatorChar, '/')
                        .replaceAll("\\.iob$", "");
                out.add(new Target(rel, f));
            }
        }
    }

    public static void run(List<Target> targets, boolean dryRun, int reach, IrisData nearest, Consumer<String> out) {
        int processed = 0;
        int changed = 0;
        int skipped = 0;
        int failed = 0;
        long totalWood = 0L;
        long totalBranches = 0L;
        long totalConverted = 0L;
        long totalRewritten = 0L;
        long totalPinned = 0L;
        long totalUnreachableBefore = 0L;
        long totalUnreachableAfter = 0L;

        int progressStep = Math.max(1, targets.size() / 20);
        int index = 0;

        for (Target t : targets) {
            index++;
            try {
                IrisObject o = load(t, nearest);
                if (o == null) {
                    out.accept("skip " + t.key() + ": failed to load");
                    skipped++;
                    continue;
                }

                long seed = TreePlausibilizer.seedOf(t.key());
                TreePlausibilizer.Result r = dryRun
                        ? TreePlausibilizer.analyze(o, seed, reach)
                        : TreePlausibilizer.apply(o, seed, reach);

                if (!dryRun && r.mutated()) {
                    File dest = o.getLoadFile() != null ? o.getLoadFile() : t.file();
                    if (dest != null) {
                        o.write(dest);
                        changed++;
                    }
                }

                processed++;
                totalWood += r.woodPlaced();
                totalBranches += r.branchesGrown();
                totalConverted += r.leavesConvertedToWood();
                totalRewritten += r.distancesRewritten();
                totalPinned += r.leavesPinnedPersistent();
                totalUnreachableBefore += r.unreachableBefore();
                totalUnreachableAfter += r.unreachableAfter();

                if (r.mutated() || targets.size() == 1) {
                    out.accept(t.key() + ": +" + r.woodPlaced() + " wood (" + r.branchesGrown() + " branches), "
                            + r.leavesConvertedToWood() + " leaves->wood, ~" + r.distancesRewritten() + " distances"
                            + (r.leavesPinnedPersistent() > 0 ? ", !" + r.leavesPinnedPersistent() + " pinned" : ""));
                }

                if (targets.size() > 1 && index % progressStep == 0) {
                    out.accept("[" + index + "/" + targets.size() + "]");
                }
            } catch (Throwable e) {
                out.accept("fail " + t.key() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                IrisLogging.reportError(e);
                failed++;
            }
        }

        out.accept("Done: " + processed + " processed, " + changed + " changed, "
                + skipped + " skipped, " + failed + " failed"
                + (dryRun ? " (dry run, nothing written)" : ""));
        out.accept("Totals: +" + totalWood + " wood (" + totalBranches + " branches), "
                + totalConverted + " leaves->wood, ~" + totalRewritten + " distances, !"
                + totalPinned + " pinned, unreachable " + totalUnreachableBefore + " -> " + totalUnreachableAfter);
    }

    private static IrisObject load(Target t, IrisData nearest) throws IOException {
        if (t.file() != null) {
            IrisObject o = new IrisObject();
            o.read(t.file());
            o.setLoadFile(t.file());
            return o;
        }
        return IrisData.loadAnyObject(t.key(), nearest);
    }
}
