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

package art.arcane.iris.core.gui;

import art.arcane.iris.core.localization.DesktopUiMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.core.IrisSettings;
import art.arcane.volmlib.util.math.M;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.locks.ReentrantLock;

public final class PregenRenderer extends JPanel implements KeyListener {
    private static final long serialVersionUID = 2094606939770332040L;

    private static final int MAX_PENDING_DRAWS = 16_384;
    private Long2ObjectLinkedOpenHashMap<Color> pending = new Long2ObjectLinkedOpenHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final int res = 512;
    private final BufferedImage image = new BufferedImage(res, res, BufferedImage.TYPE_INT_RGB);
    private final Graphics2D imageGraphics = image.createGraphics();
    private final PregenRenderSource source;
    private final Runnable onPause;
    private final Timer repaintTimer;
    private volatile boolean renderingEnabled;
    private boolean disposed;
    private volatile JFrame frame;

    private PregenRenderer(PregenRenderSource source, Runnable onPause) {
        this.source = source;
        this.onPause = onPause;
        repaintTimer = new Timer(IrisSettings.get().getGui().isMaximumPregenGuiFPS() ? 4 : 250, event -> repaint());
    }

    public static PregenRenderer open(String title, PregenRenderSource source, Runnable onPause) {
        PregenRenderer renderer = new PregenRenderer(source, onPause);
        JFrame frame = new JFrame(title);
        GuiHost.prepareFrame(frame);
        renderer.frame = frame;
        renderer.renderingEnabled = true;
        frame.addKeyListener(renderer);
        frame.add(renderer);
        frame.setSize(1000, 1000);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                renderer.disposeRenderer();
            }
        });
        frame.addWindowStateListener(event -> renderer.renderingEnabled = (event.getNewState() & Frame.ICONIFIED) == 0);
        frame.setVisible(true);
        renderer.repaintTimer.start();
        return renderer;
    }

    public void submit(int x, int z, Color color) {
        if (!renderingEnabled) {
            return;
        }

        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        lock.lock();
        try {
            pending.putAndMoveToFirst(key, color);
            if (pending.size() > MAX_PENDING_DRAWS) {
                pending.removeLast();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isVisibleFrame() {
        return renderingEnabled;
    }

    public void close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::close);
            return;
        }
        JFrame activeFrame = frame;
        if (activeFrame != null) {
            activeFrame.dispose();
        } else {
            disposeRenderer();
        }
    }

    private void disposeRenderer() {
        if (disposed) {
            return;
        }
        disposed = true;
        renderingEnabled = false;
        frame = null;
        repaintTimer.stop();
        lock.lock();
        try {
            pending.clear();
        } finally {
            lock.unlock();
        }
        imageGraphics.dispose();
    }

    @Override
    public void paint(Graphics gx) {
        Graphics2D g = (Graphics2D) gx;
        Long2ObjectLinkedOpenHashMap<Color> batch;
        lock.lock();
        try {
            batch = pending;
            pending = new Long2ObjectLinkedOpenHashMap<>();
        } finally {
            lock.unlock();
        }
        for (Long2ObjectMap.Entry<Color> entry : batch.long2ObjectEntrySet()) {
            try {
                long key = entry.getLongKey();
                draw((int) (key >> 32), (int) key, entry.getValue(), imageGraphics);
            } catch (Throwable e) {
                IrisLogging.reportError(e);
            }
        }

        g.drawImage(image, 0, 0, getParent().getWidth(), getParent().getHeight(), (img, infoflags, x, y, width, height) -> true);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Hevetica", Font.BOLD, 13));
        String[] prog = source.progress();
        int h = g.getFontMetrics().getHeight() + 5;
        int hh = 20;

        if (source.paused()) {
            g.drawString(IrisLanguage.plain(DesktopUiMessages.PREGEN_PAUSED), 20, hh += h);
            g.drawString(IrisLanguage.plain(DesktopUiMessages.PREGEN_RESUME_HINT), 20, hh += h);
        } else {
            for (String i : prog) {
                g.drawString(i, 20, hh += h);
            }
            g.drawString(IrisLanguage.plain(DesktopUiMessages.PREGEN_PAUSE_HINT), 20, hh += h);
        }

    }

    private void draw(int chunkX, int chunkZ, Color c, Graphics2D bg) {
        double pw = M.lerpInverse(source.min().getX(), source.max().getX(), chunkX);
        double ph = M.lerpInverse(source.min().getZ(), source.max().getZ(), chunkZ);
        double pwa = M.lerpInverse(source.min().getX(), source.max().getX(), chunkX + 1);
        double pha = M.lerpInverse(source.min().getZ(), source.max().getZ(), chunkZ + 1);
        int x = (int) M.lerp(0, res, pw);
        int z = (int) M.lerp(0, res, ph);
        int xa = (int) M.lerp(0, res, pwa);
        int za = (int) M.lerp(0, res, pha);
        bg.setColor(c);
        bg.fillRect(x, z, xa - x, za - z);
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    @Override
    public void keyPressed(KeyEvent e) {
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_P && onPause != null) {
            onPause.run();
        }
    }
}
