package art.arcane.iris.core.gui;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.desktop.QuitResponse;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuiHostTest {
    @Test
    public void desktopQuitIsCancelledWithoutPerformingQuit() {
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger performed = new AtomicInteger();
        QuitResponse response = new QuitResponse() {
            @Override
            public void performQuit() {
                performed.incrementAndGet();
            }

            @Override
            public void cancelQuit() {
                cancelled.incrementAndGet();
            }
        };

        GuiHost.cancelDesktopQuit(response);

        assertEquals(1, cancelled.get());
        assertEquals(0, performed.get());
    }

    @Test
    public void preparedFramesDisposeWhenClosed() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<JFrame> frameReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Iris lifecycle test");
            GuiHost.prepareFrame(frame);
            frame.setVisible(true);
            frameReference.set(frame);
        });

        JFrame frame = frameReference.get();
        assertEquals(JFrame.DISPOSE_ON_CLOSE, frame.getDefaultCloseOperation());
        assertTrue(frame.isDisplayable());
        SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        assertFalse(frame.isDisplayable());
    }
}
