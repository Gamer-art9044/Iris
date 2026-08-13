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
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.function.Consumer2;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.Position2;
import art.arcane.iris.util.common.scheduling.J;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.util.concurrent.locks.ReentrantLock;

public final class PregenRenderer extends JPanel implements KeyListener {
    private static final long serialVersionUID = 2094606939770332040L;

    // Backstop: if paint() ever stalls (iconified frame, EDT hiccup), the producer must not
    // grow this without bound — one entry per drawn chunk adds up fast on a big pregen.
    private static final int MAX_QUEUED_DRAWS = 250_000;
    private KList<Runnable> order = new KList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final int res = 512;
    private final BufferedImage image = new BufferedImage(res, res, BufferedImage.TYPE_INT_RGB);
    private final PregenRenderSource source;
    private final Runnable onPause;
    private Graphics2D bg;
    private JFrame frame;

    private PregenRenderer(PregenRenderSource source, Runnable onPause) {
        this.source = source;
        this.onPause = onPause;
    }

    public static PregenRenderer open(String title, PregenRenderSource source, Runnable onPause) {
        PregenRenderer renderer = new PregenRenderer(source, onPause);
        JFrame frame = new JFrame(title);
        renderer.frame = frame;
        frame.addKeyListener(renderer);
        frame.add(renderer);
        frame.setSize(1000, 1000);
        frame.setVisible(true);
        return renderer;
    }

    public Consumer2<Position2, Color> drawFunction() {
        return (Position2 c, Color color) -> {
            lock.lock();
            try {
                if (order.size() < MAX_QUEUED_DRAWS) {
                    order.add(() -> draw(c, color, bg));
                }
            } finally {
                lock.unlock();
            }
        };
    }

    public void submit(int x, int z, Color color) {
        drawFunction().accept(new Position2(x, z), color);
    }

    public boolean isVisibleFrame() {
        // An iconified frame still reports isVisible() but AWT stops repainting it, which
        // would stop the only queue drain while the producer keeps appending.
        JFrame activeFrame = frame;
        return activeFrame != null && activeFrame.isVisible()
                && (activeFrame.getExtendedState() & java.awt.Frame.ICONIFIED) == 0;
    }

    public void close() {
        JFrame activeFrame = frame;
        if (activeFrame != null) {
            frame = null;
            activeFrame.setVisible(false);
            activeFrame.dispose();
        }
    }

    @Override
    public void paint(Graphics gx) {
        Graphics2D g = (Graphics2D) gx;
        bg = (Graphics2D) image.getGraphics();
        // Swap the queue under the lock and drain outside it: generation threads must not
        // block on the lock while the EDT walks a large batch, and pop()-per-entry was O(N^2).
        KList<Runnable> batch;
        lock.lock();
        try {
            batch = order;
            order = new KList<>();
        } finally {
            lock.unlock();
        }
        for (Runnable r : batch) {
            try {
                r.run();
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

        J.sleep(IrisSettings.get().getGui().isMaximumPregenGuiFPS() ? 4 : 250);
        repaint();
    }

    private void draw(Position2 p, Color c, Graphics2D bg) {
        double pw = M.lerpInverse(source.min().getX(), source.max().getX(), p.getX());
        double ph = M.lerpInverse(source.min().getZ(), source.max().getZ(), p.getZ());
        double pwa = M.lerpInverse(source.min().getX(), source.max().getX(), p.getX() + 1);
        double pha = M.lerpInverse(source.min().getZ(), source.max().getZ(), p.getZ() + 1);
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
