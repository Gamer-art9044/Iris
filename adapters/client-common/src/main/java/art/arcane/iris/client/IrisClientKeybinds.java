package art.arcane.iris.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * CLIENT DIST ONLY. Static KeyMapping fields plus LWJGL constants; loading this on a dedicated server is a
 * NoClassDefFoundError. Reachable only from the per-loader client shims, which are Dist.CLIENT gated.
 * ModdedClientPackageIsolationTest enforces that no modded or nativegen class reaches it. No @Environment
 * annotation: net.fabricmc.api is absent from the Forge and NeoForge compile classpath.
 */
public final class IrisClientKeybinds {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(IrisClient.KEYBIND_CATEGORY_ID);
    public static final KeyMapping TOGGLE_HUD = new KeyMapping(IrisClient.KEYBIND_TOGGLE_HUD, GLFW.GLFW_KEY_H, CATEGORY);
    public static final KeyMapping OPEN_MAP = new KeyMapping(IrisClient.KEYBIND_OPEN_MAP, GLFW.GLFW_KEY_M, CATEGORY);
    public static final KeyMapping TOGGLE_WHAT = new KeyMapping(IrisClient.KEYBIND_TOGGLE_WHAT, GLFW.GLFW_KEY_J, CATEGORY);

    private IrisClientKeybinds() {
    }

    public static KeyMapping.Category category() {
        return CATEGORY;
    }

    public static void pollToggle() {
        while (TOGGLE_HUD.consumeClick()) {
            IrisClient.toggleHud();
        }
        while (TOGGLE_WHAT.consumeClick()) {
            IrisClient.toggleWhat();
        }
        while (OPEN_MAP.consumeClick()) {
            openVisionScreen();
        }
    }

    private static void openVisionScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        minecraft.setScreenAndShow(new IrisVisionScreen());
    }
}
