package art.arcane.iris.modded.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IrisModdedCommandParityTest {
    @Test
    public void registersPluginParityWhatCommandsAndAliases() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        IrisModdedCommands.register(dispatcher);

        CommandNode<CommandSourceStack> iris = child(dispatcher.getRoot(), "iris");
        CommandNode<CommandSourceStack> what = child(iris, "what");
        child(what, "here");
        child(what, "biome");
        child(what, "region");
        child(what, "block");
        child(what, "hand");
        child(what, "markers");
        child(iris, "dust");
        child(iris, "d");
        child(iris, "create");
        child(iris, "c");
        child(iris, "teleport");
        child(iris, "tp");
        child(iris, "height");
        child(iris, "worlds");
        child(iris, "accesslist");
        child(child(iris, "goto"), "unregistered");

        CommandNode<CommandSourceStack> edit = child(iris, "edit");
        child(edit, "b");
        child(edit, "r");
        child(edit, "d");
        CommandNode<CommandSourceStack> studio = child(iris, "studio");
        child(studio, "package");
        child(studio, "pkg");

        CommandNode<CommandSourceStack> download = child(iris, "download");
        CommandNode<CommandSourceStack> source = child(download, "source");
        assertTrue(source.getChildren().isEmpty());

        assertSame(iris, child(dispatcher.getRoot(), "ir").getRedirect());
        assertSame(iris, child(dispatcher.getRoot(), "irs").getRedirect());
    }

    @Test
    public void downloadRequestAcceptsOnlyBuiltInsAndZipLinks() {
        IrisModdedCommands.DownloadRequest overworld = IrisModdedCommands.parseDownloadRequest("pack=overworld");
        IrisModdedCommands.DownloadRequest underworld = IrisModdedCommands.parseDownloadRequest("pack=UNDERWORLD");
        IrisModdedCommands.DownloadRequest link = IrisModdedCommands.parseDownloadRequest(
                "link=https://packs.example.test/custom.zip?token=a=b"
        );

        assertNotNull(overworld);
        assertEquals("overworld", overworld.pack());
        assertNotNull(underworld);
        assertEquals("underworld", underworld.pack());
        assertNotNull(link);
        assertEquals("https://packs.example.test/custom.zip?token=a=b", link.url());
        assertNull(IrisModdedCommands.parseDownloadRequest("overworld"));
        assertNull(IrisModdedCommands.parseDownloadRequest("pack=custom"));
        assertNull(IrisModdedCommands.parseDownloadRequest("link=https://packs.example.test/custom.tar.gz"));
        assertNull(IrisModdedCommands.parseDownloadRequest("pack=underworld branch=stable"));
        assertNull(IrisModdedCommands.parseDownloadRequest("pack=underworld overwrite=true"));
    }

    @Test
    public void helpDocumentsParityCommandsAndPlatformStubs() {
        assertTrue(ModdedCommandHelp.documents("what", "here"));
        assertTrue(ModdedCommandHelp.documents("what", "biome"));
        assertTrue(ModdedCommandHelp.documents("what", "region"));
        assertTrue(ModdedCommandHelp.documents("what", "block"));
        assertTrue(ModdedCommandHelp.documents("what", "hand"));
        assertTrue(ModdedCommandHelp.documents("what", "markers"));
        assertTrue(ModdedCommandHelp.documents("", "dust"));
        assertTrue(ModdedCommandHelp.documents("", "teleport"));
        assertTrue(ModdedCommandHelp.documents("", "c"));
        assertTrue(ModdedCommandHelp.documents("edit", "b"));
        assertTrue(ModdedCommandHelp.documents("studio", "pkg"));
        assertTrue(ModdedCommandHelp.documents("object", "we"));
        assertTrue(ModdedCommandHelp.documents("world", "mainworld"));
        assertTrue(ModdedCommandHelp.documents("goto", "unregistered"));
    }

    @Test
    public void categoryLiteralsFallBackToHelpWithoutArguments() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

        IrisModdedCommands.register(dispatcher);

        CommandNode<CommandSourceStack> iris = child(dispatcher.getRoot(), "iris");
        assertNotNull("iris", iris.getCommand());
        for (String category : new String[]{"help", "find", "goto", "pregen", "pregenerate", "object", "o",
                "edit", "studio", "std", "s", "pack", "pk", "world", "w", "datapack", "datapacks", "dp",
                "structure", "struct", "str", "developer", "dev"}) {
            assertNotNull(category, child(iris, category).getCommand());
        }
    }

    @Test
    public void consoleHelpListsEveryEntryWithInlineUsageAndDescription() {
        List<String> root = ModdedCommandHelp.consoleLines("");

        assertTrue(root.stream().anyMatch((String line) -> line.startsWith("/iris developer (dev) - ")));
        assertTrue(root.stream().anyMatch((String line) -> line.startsWith("/iris teleport <dimension> [player] (tp) - ")));
        assertTrue(ModdedCommandHelp.consoleLines("pregen").stream().anyMatch((String line) ->
                line.startsWith("/iris pregen start <radius> [dimension] [at] [x] [z] [gui] [sync] [nocache] - ")));
    }

    private static CommandNode<CommandSourceStack> child(
            CommandNode<CommandSourceStack> parent, String name) {
        CommandNode<CommandSourceStack> child = parent.getChild(name);
        assertNotNull(name, child);
        return child;
    }
}
