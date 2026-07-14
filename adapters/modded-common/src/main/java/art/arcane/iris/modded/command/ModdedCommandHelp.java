/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

package art.arcane.iris.modded.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static art.arcane.iris.modded.command.ModdedCommandFeedback.BACK;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.CATEGORY;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.DARK_GREEN;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.DESCRIPTION;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.DESCRIPTION_ICON;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.EXAMPLE_ICON;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.HOVER_TYPE;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.OPTIONAL;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.PARAMETER;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.PARAMETER_ALT;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.REQUIRED;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.REQUIRED_TEXT;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.USAGE;
import static art.arcane.iris.modded.command.ModdedCommandFeedback.USAGE_ICON;

final class ModdedCommandHelp {
    private static final Map<String, List<Entry>> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("", List.of(
                Entry.command("version", "", "Print version information"),
                Entry.command("info", "[dimension]", "List loaded Iris dimensions and pack details"),
                Entry.command("what", "[block|hand|markers]", "Inspect the Iris biome, region, cave biome, surface and chunk at your position, the block you look at, your held item, or nearby markers"),
                Entry.group("find", "Find and teleport to Iris biomes, regions, objects, Iris structures, native structures and points of interest", "goto"),
                Entry.command("tp", "<dimension> [player]", "Teleport yourself or a named player into a loaded Iris dimension"),
                Entry.command("evacuate", "[dimension]", "Teleport every player out of an Iris dimension to the primary world spawn"),
                Entry.command("seed", "", "Print world and engine seed information"),
                Entry.command("debug", "", "Toggle Iris debug logging and save settings.json"),
                Entry.command("reload", "", "Reload settings.json (also hotloaded automatically every 3s)"),
                Entry.command("download", "<pack> [branch]", "Download a pack project", "dl"),
                Entry.command("metrics", "", "Print generation metrics for your current Iris dimension", "measure"),
                Entry.command("regen", "[radius]", "Delete and regenerate nearby chunks in place", "rg"),
                Entry.group("pregen", "Pregenerate an Iris dimension", "pregenerate"),
                Entry.command("wand", "", "Get an Iris object wand"),
                Entry.group("object", "Object wand, save, paste, analyze and undo tools", "o"),
                Entry.group("edit", "Open pack biome, region and dimension json files in your desktop editor"),
                Entry.command("create", "<name> <pack|pack:dimensionKey> [seed]", "Create and inject a persistent Iris dimension; quote pack:dimensionKey to pick a specific pack dimension"),
                Entry.group("studio", "Pack project creation, packaging and reports", "std", "s"),
                Entry.group("pack", "Pack validation and maintenance", "pk"),
                Entry.group("world", "Runtime Iris dimension creation, removal and status", "w"),
                Entry.group("datapack", "World datapack install and status helpers", "datapacks", "dp"),
                Entry.group("structure", "Iris structure index, info and placement tools", "struct", "str"),
                Entry.command("goldenhash", "[radius] [threads] [capture|verify]", "Generate deterministic block hashes for parity testing", "gold"),
                Entry.group("developer", "Developer diagnostics: Sentry test, network interfaces, region-file scan", "dev")
        ));
        SECTIONS.put("find", List.of(
                Entry.command("biome", "<key>", "Find an Iris biome"),
                Entry.command("region", "<key>", "Find an Iris region"),
                Entry.command("object", "<key>", "Find an object placement"),
                Entry.command("structure", "<key>", "Find an Iris-placed or native/datapack structure"),
                Entry.command("poi", "<type>", "Find a supported point of interest")
        ));
        SECTIONS.put("goto", SECTIONS.get("find"));
        SECTIONS.put("edit", List.of(
                Entry.command("biome", "[key]", "Open a biome json in your desktop editor; no key opens the biome at your position"),
                Entry.command("region", "[key]", "Open a region json in your desktop editor; no key opens the region at your position"),
                Entry.command("dimension", "", "Open the current pack's dimension json in your desktop editor")
        ));
        SECTIONS.put("pregen", List.of(
                Entry.command("start", "<radius> [dimension] [at] [x] [z] [gui] [sync] [nocache]", "Start pregeneration; radius in blocks, resumable checkpoint cache on by default, center via 'at <x> <z>', flags compose in any order"),
                Entry.command("stop", "", "Stop the active pregeneration task", "x"),
                Entry.command("pause", "", "Pause or resume pregeneration", "resume"),
                Entry.command("status", "", "Show pregeneration status")
        ));
        SECTIONS.put("pregenerate", SECTIONS.get("pregen"));
        SECTIONS.put("object", List.of(
                Entry.command("wand", "", "Get an Iris object wand"),
                Entry.command("dust", "", "Get dust that reveals object placements", "d"),
                Entry.command("save", "[overwrite] <name>", "Save the selected wand volume as an object"),
                Entry.command("paste", "[at] [x] [y] [z] [rotate] [degrees] <key>", "Paste an object at your position or a given position, optionally rotated"),
                Entry.command("expand", "[amount]", "Expand the wand selection in your looking direction"),
                Entry.command("contract", "[amount]", "Contract the wand selection in your looking direction", "-"),
                Entry.command("shift", "[amount]", "Shift the wand selection in your looking direction"),
                Entry.command("position1", "[look]", "Set selection point 1", "p1"),
                Entry.command("position2", "[look]", "Set selection point 2", "p2"),
                Entry.command("x+y", "", "Autoselect up and out", "xpy"),
                Entry.command("x&y", "", "Autoselect up, down and out", "xay"),
                Entry.command("analyze", "<key>", "Show object composition"),
                Entry.command("shrink", "<key>", "Shrink an object to its minimum size"),
                Entry.command("undo", "[amount]", "Undo pasted objects", "u")
        ));
        SECTIONS.put("o", SECTIONS.get("object"));
        SECTIONS.put("studio", List.of(
                Entry.command("create", "<name> [template]", "Create a new pack project", "+"),
                Entry.command("package", "[pack]", "Package a dimension into a compressed format"),
                Entry.command("version", "[pack]", "Print a pack version"),
                Entry.command("regions", "[radius]", "Calculate nearby region distribution"),
                Entry.command("open", "<pack> [seed]", "Open a temporary studio dimension for a pack", "o"),
                Entry.command("close", "", "Close the open studio dimension and discard its world", "x"),
                Entry.command("tpstudio", "", "Teleport into the open studio dimension", "stp"),
                Entry.command("status", "", "Show the open studio dimension and its pack"),
                Entry.command("noise", "[generator] [seed]", "Open the Noise Explorer GUI on the server display", "nmap"),
                Entry.command("map", "", "Open the Vision map GUI on the server display", "render"),
                Entry.command("vscode", "[pack]", "Regenerate the .code-workspace for a pack and open it in your desktop editor", "vsc"),
                Entry.command("update", "[pack]", "Regenerate the .code-workspace for a pack"),
                Entry.command("importvanilla", "", "Explain vanilla import workflow", "importv", "iv")
        ));
        SECTIONS.put("std", SECTIONS.get("studio"));
        SECTIONS.put("s", SECTIONS.get("studio"));
        SECTIONS.put("pack", List.of(
                Entry.command("validate", "[pack]", "Validate a pack or every pack", "v"),
                Entry.command("cleanup", "<pack> [apply]", "Preview or quarantine unused-resource candidates", "c"),
                Entry.command("restore", "<pack> [apply]", "Preview or restore the latest quarantine", "r"),
                Entry.command("status", "[pack]", "Show cached validation status", "s")
        ));
        SECTIONS.put("pk", SECTIONS.get("pack"));
        SECTIONS.put("world", List.of(
                Entry.command("enable", "<dimension> <pack|pack:dimensionKey> [seed|random]", "Create and inject a persistent Iris dimension at runtime; downloads the pack if missing, quote pack:dimensionKey to pick a specific pack dimension", "create"),
                Entry.command("replace-overworld", "<pack|pack:dimensionKey> [seed|random]", "Inject an Iris primary world and route players there instead of the vanilla overworld"),
                Entry.command("disable", "<dimension>", "Evacuate and unload an Iris dimension; world data on disk is kept for re-enabling"),
                Entry.command("delete", "<dimension>", "Disable an Iris dimension and wipe its chunk and mantle data from disk", "remove", "rm"),
                Entry.command("list", "", "List loaded Iris dimensions", "ls"),
                Entry.command("status", "", "Show loaded Iris dimensions and the configured primary world")
        ));
        SECTIONS.put("w", SECTIONS.get("world"));
        SECTIONS.put("datapack", List.of(
                Entry.command("status", "", "Check loaded Iris dimension type overrides"),
                Entry.command("install", "", "Install dimension type overrides for loaded Iris dimensions"),
                Entry.command("list", "", "List configured and installed datapacks", "ls"),
                Entry.command("ingest", "", "Explain Bukkit datapack ingest workflow", "pull"),
                Entry.command("remove", "<id>", "Explain datapack removal workflow", "rm")
        ));
        SECTIONS.put("datapacks", SECTIONS.get("datapack"));
        SECTIONS.put("dp", SECTIONS.get("datapack"));
        SECTIONS.put("structure", List.of(
                Entry.command("list", "", "Regenerate structure-index.json", "ls"),
                Entry.command("info", "<key>", "Resolve an Iris structure graph and report bounds"),
                Entry.command("place", "<key>", "Assemble and place an Iris structure at your location", "p"),
                Entry.command("import", "", "Explain Bukkit structure import workflow", "import-all", "reimport", "imp", "all"),
                Entry.command("capture", "", "Explain Bukkit structure capture workflow", "cap"),
                Entry.command("verify", "[key]", "Report native and Iris structure reachability in the current dimension", "locateall")
        ));
        SECTIONS.put("struct", SECTIONS.get("structure"));
        SECTIONS.put("str", SECTIONS.get("structure"));
        SECTIONS.put("developer", List.of(
                Entry.command("sentry", "", "Send a test exception to the Iris error reporter"),
                Entry.command("network", "", "List network interfaces and their addresses", "ip")
        ));
        SECTIONS.put("dev", SECTIONS.get("developer"));
    }

    private ModdedCommandHelp() {
    }

    static int send(CommandSourceStack source, String path) {
        String normalized = normalize(path);
        List<Entry> entries = SECTIONS.get(normalized);
        if (entries == null) {
            ModdedCommandFeedback.fail(source, "Unknown Iris help section: " + normalized);
            return 0;
        }

        ModdedCommandFeedback.clear(source);

        sendHeader(source, normalized);
        if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) {
            ModdedCommandFeedback.send(source, opNotice());
        }
        if (!normalized.isEmpty()) {
            ModdedCommandFeedback.send(source, backButton(normalized));
        }
        for (Entry entry : entries) {
            ModdedCommandFeedback.send(source, line(normalized, entry));
        }
        ModdedCommandFeedback.ok(source, footer());
        return 1;
    }

    private static void sendHeader(CommandSourceStack source, String path) {
        String title = path.isEmpty() ? "/iris" : "/iris " + path;
        ModdedCommandFeedback.send(source, ModdedCommandFeedback.header(title));
    }

    private static MutableComponent backButton(String path) {
        String parent = parentPath(path);
        String command = parent.isEmpty() ? "/iris" : "/iris help " + parent;
        MutableComponent hover = Component.empty()
                .append(text("Click to go back to ", DARK_GREEN))
                .append(text(parent.isEmpty() ? "Iris" : parent, PARAMETER_ALT));
        return text("〈 Back", BACK).withStyle((style) -> style
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private static MutableComponent line(String path, Entry entry) {
        MutableComponent row = Component.empty();
        row.append(clickableCommand(path, entry));
        row.append(nodes(entry));
        return row;
    }

    private static MutableComponent clickableCommand(String path, Entry entry) {
        String parent = path.isEmpty() ? "/iris" : "/iris " + path;
        String command = parent + " " + entry.name();
        String suggestion = entry.usage().isBlank() ? command : command + " " + entry.usage();
        ClickEvent clickEvent = entry.group() ? new ClickEvent.RunCommand(command) : new ClickEvent.SuggestCommand(suggestion);
        MutableComponent hover = entryHover(path, entry, suggestion);
        MutableComponent display = Component.empty();
        display.append(text(parent + " >", 0xFFFFFF));
        display.append(text("⇀", DARK_GREEN));
        display.append(text(" " + entry.name(), PARAMETER_ALT));
        return display.withStyle((style) -> style
                .withClickEvent(clickEvent)
                .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private static MutableComponent nodes(Entry entry) {
        if (entry.group()) {
            return text(" - Category of Commands", CATEGORY);
        }

        List<String> tokens = usageTokens(entry.usage());
        if (tokens.isEmpty()) {
            return Component.empty();
        }

        MutableComponent nodes = Component.empty();
        for (String token : tokens) {
            nodes.append(Component.literal(" "));
            nodes.append(parameter(token));
        }
        return nodes;
    }

    private static MutableComponent parameter(String token) {
        String name = parameterName(token);
        boolean required = token.startsWith("<");
        MutableComponent title = Component.empty();
        if (required) {
            title.append(text("[", REQUIRED, true, false));
            title.append(text(name, PARAMETER, false, false));
            title.append(text("]", REQUIRED, true, false));
        } else {
            title.append(text("⊰", OPTIONAL));
            title.append(text(name, PARAMETER));
            title.append(text("⊱", OPTIONAL));
        }

        MutableComponent hover = Component.empty();
        hover.append(text(name, PARAMETER));
        hover.append(Component.literal("\n"));
        hover.append(text("✎ ", DESCRIPTION_ICON));
        hover.append(text("Command parameter", DESCRIPTION));
        hover.append(Component.literal("\n"));
        if (required) {
            hover.append(text("⚠ ", REQUIRED));
            hover.append(text("This parameter is required.", REQUIRED_TEXT));
        } else {
            hover.append(text("✔ ", DESCRIPTION_ICON));
            hover.append(text("This parameter is optional.", USAGE));
        }
        hover.append(Component.literal("\n"));
        hover.append(text("✢ ", DARK_GREEN));
        hover.append(text("This parameter is read as text by Brigadier.", HOVER_TYPE));

        return title.withStyle((style) -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private static MutableComponent entryHover(String path, Entry entry, String suggestion) {
        MutableComponent hover = Component.empty();
        hover.append(text(names(entry), PARAMETER));
        hover.append(Component.literal("\n"));
        hover.append(text("✎ ", DESCRIPTION_ICON));
        hover.append(text(entry.description(), DESCRIPTION));
        hover.append(Component.literal("\n"));
        hover.append(text("✒ ", USAGE_ICON));
        if (entry.group()) {
            hover.append(text("This is a command category. Click to run.", USAGE));
        } else if (entry.usage().isBlank()) {
            hover.append(text("There are no parameters. Click to type command.", USAGE));
        } else {
            hover.append(text("Hover over all of the parameters to learn more.", USAGE));
            hover.append(Component.literal("\n"));
            hover.append(text("✦ ", EXAMPLE_ICON));
            hover.append(text(suggestion, PARAMETER));
        }

        String parent = path.isEmpty() ? "/iris" : "/iris " + path;
        if (entry.aliases().length > 0) {
            hover.append(Component.literal("\n"));
            hover.append(text("Aliases: ", DARK_GREEN));
            List<String> aliases = new ArrayList<>(entry.aliases().length);
            for (String alias : entry.aliases()) {
                aliases.add(parent + " " + alias);
            }
            hover.append(text(String.join(", ", aliases), PARAMETER_ALT));
        }
        return hover;
    }

    private static MutableComponent opNotice() {
        MutableComponent notice = Component.empty();
        notice.append(text("⚠ ", REQUIRED));
        notice.append(text("Iris commands need operator permission (level 2). ", REQUIRED_TEXT));
        notice.append(text("Run ", DESCRIPTION));
        notice.append(text("/op <you>", PARAMETER_ALT));
        notice.append(text(" from the console (or enable cheats in singleplayer); ", DESCRIPTION));
        notice.append(text("until then these commands will not run or tab-complete.", USAGE));
        return notice;
    }

    private static MutableComponent footer() {
        return ModdedCommandFeedback.footer();
    }

    private static MutableComponent text(String value, int color) {
        return ModdedCommandFeedback.text(value, color, false, false);
    }

    private static MutableComponent text(String value, int color, boolean bold, boolean strikethrough) {
        return ModdedCommandFeedback.text(value, color, bold, strikethrough);
    }

    private static List<String> usageTokens(String usage) {
        if (usage == null || usage.isBlank()) {
            return List.of();
        }
        return List.of(usage.trim().split("\\s+"));
    }

    private static String names(Entry entry) {
        if (entry.aliases().length == 0) {
            return entry.name();
        }

        List<String> names = new ArrayList<>(entry.aliases().length + 1);
        names.add(entry.name());
        for (String alias : entry.aliases()) {
            names.add(alias);
        }
        return String.join(", ", names);
    }

    private static String parameterName(String token) {
        String name = token;
        if ((name.startsWith("<") && name.endsWith(">")) || (name.startsWith("[") && name.endsWith("]"))) {
            name = name.substring(1, name.length() - 1);
        }
        return name;
    }

    private static String parentPath(String path) {
        int lastSpace = path.lastIndexOf(' ');
        if (lastSpace <= 0) {
            return "";
        }
        return path.substring(0, lastSpace);
    }

    private static String normalize(String path) {
        if (path == null) {
            return "";
        }

        String normalized = path.trim().toLowerCase();
        if (normalized.startsWith("/iris ")) {
            normalized = normalized.substring(6).trim();
        } else if (normalized.equals("/iris")) {
            normalized = "";
        }

        if (normalized.startsWith("help ")) {
            normalized = normalized.substring(5).trim();
        }

        int space = normalized.indexOf(' ');
        if (space >= 0) {
            normalized = normalized.substring(0, space);
        }

        return normalized;
    }

    private record Entry(String name, String usage, String description, boolean group, String... aliases) {
        static Entry command(String name, String usage, String description, String... aliases) {
            return new Entry(name, usage, description, false, aliases);
        }

        static Entry group(String name, String description, String... aliases) {
            return new Entry(name, "", description, true, aliases);
        }
    }
}
