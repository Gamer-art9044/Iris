package art.arcane.iris.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CLIENT DIST ONLY. References net.minecraft.client and must never be reachable from
 * art.arcane.iris.modded or art.arcane.iris.nativegen. ModdedClientPackageIsolationTest enforces that
 * direction; there is no @Environment annotation because net.fabricmc.api is not on the Forge or NeoForge
 * compile classpath and this source set builds for all three loaders.
 */
public final class IrisClientHud {
    private IrisClientHud() {
    }

    /**
     * Per client tick, independent of the HUD layer. Toasts are pumped here rather than from
     * {@link #render(GuiGraphicsExtractor)} because the whole layered HUD draw is skipped while hideGui (F1)
     * is on, which would silently strand every queued toast until the player pressed F1 again.
     */
    public static void tick() {
        IrisToastPresenter.pump();
    }

    public static void render(GuiGraphicsExtractor graphics) {
        IrisPregenHud.render(graphics);
        IrisWhatOverlay.render(graphics);
    }
}
