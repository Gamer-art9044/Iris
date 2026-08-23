package art.arcane.iris.core.gui;

import art.arcane.iris.core.localization.DesktopUiMessages;
import art.arcane.iris.engine.framework.render.RenderType;
import art.arcane.volmlib.util.localization.MessageKey;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class VisionRenderTypeTest {
    @Test
    public void everyRenderTypeHasALocalizedVisionLabel() {
        for (RenderType type : RenderType.values()) {
            MessageKey key = VisionGUI.modeKey(type);
            assertNotNull(type.name(), key);
            assertTrueCatalogMember(key);
        }
    }

    @Test
    public void riverRenderTypeUsesTheRiverNetworkLabel() {
        assertSame(DesktopUiMessages.VISION_MODE_RIVER, VisionGUI.modeKey(RenderType.RIVER));
        assertEquals("iris.desktop.vision.mode.river", DesktopUiMessages.VISION_MODE_RIVER.id());
    }

    private static void assertTrueCatalogMember(MessageKey key) {
        assertEquals(key.id(), 1, DesktopUiMessages.keys().stream()
                .filter(candidate -> candidate.id().equals(key.id()))
                .count());
    }
}
