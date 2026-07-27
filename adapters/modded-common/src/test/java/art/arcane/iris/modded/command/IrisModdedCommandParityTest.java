package art.arcane.iris.modded.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
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

        CommandNode<CommandSourceStack> edit = child(iris, "edit");
        child(edit, "b");
        child(edit, "r");
        child(edit, "d");
        CommandNode<CommandSourceStack> studio = child(iris, "studio");
        child(studio, "package");
        child(studio, "pkg");

        CommandNode<CommandSourceStack> download = child(iris, "download");
        CommandNode<CommandSourceStack> pack = child(download, "pack");
        child(pack, "force");
        child(pack, "overwrite");
        CommandNode<CommandSourceStack> branch = child(pack, "branch");
        child(branch, "force");
        child(branch, "overwrite");

        assertSame(iris, child(dispatcher.getRoot(), "ir").getRedirect());
        assertSame(iris, child(dispatcher.getRoot(), "irs").getRedirect());
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
    }

    private static CommandNode<CommandSourceStack> child(
            CommandNode<CommandSourceStack> parent, String name) {
        CommandNode<CommandSourceStack> child = parent.getChild(name);
        assertNotNull(name, child);
        return child;
    }
}
