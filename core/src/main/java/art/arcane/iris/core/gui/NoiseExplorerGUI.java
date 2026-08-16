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
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisGenerator;
import art.arcane.iris.engine.object.NoiseStyle;
import art.arcane.volmlib.util.function.Function2;
import art.arcane.volmlib.util.math.M;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.math.RollingSequence;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.iris.util.project.noise.CNG;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.scheduling.PrecisionStopwatch;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class NoiseExplorerGUI extends JPanel implements MouseWheelListener {

    private static final long serialVersionUID = 2094606939770332040L;
    private static final Color BG = new Color(24, 24, 28);
    private static final Color SIDEBAR_BG = new Color(20, 20, 24);
    private static final Color SIDEBAR_SELECTED = new Color(40, 50, 70);
    private static final Color SIDEBAR_ITEM_COLOR = new Color(170, 170, 185);
    private static final Color SEARCH_BG = new Color(30, 30, 38);
    private static final Color SEARCH_FG = new Color(180, 180, 190);
    private static final Color STATUS_BG = new Color(32, 32, 38, 230);
    private static final Color STATUS_TEXT = new Color(180, 180, 190);
    private static final Color ACCENT = new Color(90, 140, 255);
    private static final Color SEPARATOR = new Color(40, 40, 50);
    private static final Font STATUS_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font SIDEBAR_HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    private static final Font SIDEBAR_ITEM_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font SEARCH_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final int SIDEBAR_WIDTH = 240;
    private static final int[] HSB_LUT = new int[256];

    private static final NoiseCategory[] CATEGORY_ORDER = {
            NoiseCategory.PACK_GENERATORS, NoiseCategory.SIMPLEX, NoiseCategory.PERLIN,
            NoiseCategory.CELLULAR, NoiseCategory.IRIS, NoiseCategory.CLOVER, NoiseCategory.HEXAGON,
            NoiseCategory.VASCULAR, NoiseCategory.GLOBE, NoiseCategory.CUBIC, NoiseCategory.FRACTAL,
            NoiseCategory.STATIC, NoiseCategory.NOWHERE, NoiseCategory.SIERPINSKI, NoiseCategory.UTILITY,
            NoiseCategory.OTHER
    };

    static {
        for (int i = 0; i < 256; i++) {
            float n = i / 255f;
            HSB_LUT[i] = Color.HSBtoRGB(0.666f - n * 0.666f, 1f, 1f - n * 0.8f);
        }
    }

    private final RollingSequence fpsHistory = new RollingSequence(60);
    private final boolean colorMode = IrisSettings.get().getGui().colorMode;
    private final MultiBurst gx = MultiBurst.burst;
    private final Runnable hotloadHook = this::refreshGenerator;
    private double scale = 1;
    private double animScale = 10;
    private double ox = 0;
    private double oz = 0;
    private double animOx = 0;
    private double animOz = 0;
    private double lastMouseX = Double.MAX_VALUE;
    private double lastMouseZ = Double.MAX_VALUE;
    private double time = 0;
    private double animTime = 0;
    private int imgWidth = 0;
    private int imgHeight = 0;
    private BufferedImage img;
    private CNG cng = NoiseStyle.STATIC.create(new RNG(RNG.r.nextLong()));
    private Function2<Double, Double, Double> generator;
    private Supplier<Function2<Double, Double, Double>> loader;
    private String currentName = "STATIC";

    public NoiseExplorerGUI() {
        GuiHost.get().registerHotloadHook(hotloadHook);
        setBackground(BG);
        addMouseWheelListener(this);
        addMouseMotionListener(new MouseMotionListener() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Point cp = e.getPoint();
                lastMouseX = cp.getX();
                lastMouseZ = cp.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point cp = e.getPoint();
                ox += (lastMouseX - cp.getX()) * scale;
                oz += (lastMouseZ - cp.getY()) * scale;
                lastMouseX = cp.getX();
                lastMouseZ = cp.getY();
            }
        });
    }

    public static void launch() {
        Engine engine = GuiHost.get().findActiveEngine();
        EventQueue.invokeLater(() -> {
            NoiseExplorerGUI nv = new NoiseExplorerGUI();
            buildFrame(IrisLanguage.plain(DesktopUiMessages.NOISE_TITLE), nv, engine, null, null);
        });
    }

    public static void launch(Supplier<Function2<Double, Double, Double>> gen, String genName) {
        Engine engine = GuiHost.get().findActiveEngine();
        EventQueue.invokeLater(() -> {
            NoiseExplorerGUI nv = new NoiseExplorerGUI();
            nv.loader = gen;
            nv.generator = gen.get();
            nv.currentName = genName;
            buildFrame(IrisLanguage.plain(DesktopUiMessages.NOISE_TITLE_GENERATOR, MessageArgument.untrusted("generator", genName)), nv, engine, gen, genName);
        });
    }

    private static JFrame buildFrame(String title, NoiseExplorerGUI nv, Engine engine,
                                     Supplier<Function2<Double, Double, Double>> customGen, String customName) {
        JFrame frame = new JFrame(title);
        GuiHost.prepareFrame(frame);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout());

        JPanel sidebar = buildSidebar(nv, engine, customGen, customName);
        frame.add(sidebar, BorderLayout.WEST);
        frame.add(nv, BorderLayout.CENTER);

        frame.setSize(1440, 820);
        frame.setMinimumSize(new Dimension(640, 480));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                GuiHost.get().unregisterHotloadHook(nv.hotloadHook);
            }
        });
        return frame;
    }

    private static JPanel buildSidebar(NoiseExplorerGUI nv, Engine engine,
                                       Supplier<Function2<Double, Double, Double>> customGen, String customName) {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        sidebar.setBackground(SIDEBAR_BG);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, SEPARATOR));

        JTextField search = new JTextField();
        search.setBackground(SEARCH_BG);
        search.setForeground(SEARCH_FG);
        search.setCaretColor(SEARCH_FG);
        search.setFont(SEARCH_FONT);
        search.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, SEPARATOR),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        search.putClientProperty("JTextField.placeholderText", IrisLanguage.plain(DesktopUiMessages.NOISE_SEARCH));

        LinkedHashMap<String, List<ListItem>> categories = buildCategoryMap(nv, engine, customGen, customName);
        DefaultListModel<ListItem> model = new DefaultListModel<>();
        populateModel(model, categories, "");

        JList<ListItem> list = new JList<>(model);
        list.setBackground(SIDEBAR_BG);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SidebarCellRenderer());
        list.setFixedCellHeight(-1);

        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            ListItem selected = list.getSelectedValue();
            if (selected != null && !selected.header && selected.action != null) {
                selected.action.run();
            }
        });

        search.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String text = search.getText().trim();
                populateModel(model, categories, text);
            }

            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getVerticalScrollBar().setBackground(SIDEBAR_BG);

        sidebar.add(search, BorderLayout.NORTH);
        sidebar.add(scrollPane, BorderLayout.CENTER);
        return sidebar;
    }

    private static void populateModel(DefaultListModel<ListItem> model, LinkedHashMap<String, List<ListItem>> categories, String filter) {
        model.clear();
        String lower = filter.toLowerCase();
        for (Map.Entry<String, List<ListItem>> entry : categories.entrySet()) {
            List<ListItem> matching = new ArrayList<>();
            for (ListItem item : entry.getValue()) {
                if (lower.isEmpty() || item.text.toLowerCase().contains(lower) || item.rawName.toLowerCase().contains(lower)) {
                    matching.add(item);
                }
            }
            if (!matching.isEmpty()) {
                model.addElement(new ListItem(entry.getKey(), entry.getKey(), true, null));
                for (ListItem item : matching) {
                    model.addElement(item);
                }
            }
        }
    }

    private static LinkedHashMap<String, List<ListItem>> buildCategoryMap(NoiseExplorerGUI nv, Engine engine,
                                                                          Supplier<Function2<Double, Double, Double>> customGen, String customName) {
        LinkedHashMap<String, List<ListItem>> categories = new LinkedHashMap<>();

        if (customGen != null && customName != null) {
            List<ListItem> custom = new ArrayList<>();
            custom.add(new ListItem(customName, customName, false, () -> {
                nv.generator = customGen.get();
                nv.loader = customGen;
                nv.currentName = customName;
            }));
            categories.put(IrisLanguage.plain(DesktopUiMessages.NOISE_CATEGORY_CUSTOM), custom);
        }

        Map<NoiseCategory, List<NoiseStyle>> styleGroups = new LinkedHashMap<>();
        for (NoiseStyle style : NoiseStyle.values()) {
            NoiseCategory cat = categorize(style);
            styleGroups.computeIfAbsent(cat, k -> new ArrayList<>()).add(style);
        }

        if (engine != null && !engine.isClosed()) {
            List<ListItem> genItems = new ArrayList<>();
            try {
                IrisData data = engine.getData();
                String[] keys = data.getGeneratorLoader().getPossibleKeys();
                Arrays.sort(keys);
                for (String key : keys) {
                    IrisGenerator gen = data.getGeneratorLoader().load(key);
                    if (gen != null) {
                        long seed = new RNG(12345).nextParallelRNG(3245).lmax();
                        genItems.add(new ListItem(formatName(key), key, false, () -> {
                            nv.generator = (x, z) -> gen.getHeight(x, z, seed);
                            nv.loader = null;
                            nv.currentName = key;
                        }));
                    }
                }
            } catch (Throwable ignored) {}
            if (!genItems.isEmpty()) {
                categories.put(categoryLabel(NoiseCategory.PACK_GENERATORS), genItems);
            }
        }

        for (NoiseCategory cat : CATEGORY_ORDER) {
            if (cat == NoiseCategory.PACK_GENERATORS) continue;
            List<NoiseStyle> styles = styleGroups.get(cat);
            if (styles != null && !styles.isEmpty()) {
                List<ListItem> items = new ArrayList<>();
                for (NoiseStyle style : styles) {
                    items.add(new ListItem(formatName(style.name()), style.name(), false, () -> {
                        nv.cng = style.create(RNG.r.nextParallelRNG(RNG.r.imax()));
                        nv.generator = null;
                        nv.loader = null;
                        nv.currentName = style.name();
                    }));
                }
                categories.put(categoryLabel(cat), items);
            }
        }

        for (Map.Entry<NoiseCategory, List<NoiseStyle>> entry : styleGroups.entrySet()) {
            String category = categoryLabel(entry.getKey());
            if (!categories.containsKey(category)) {
                List<ListItem> items = new ArrayList<>();
                for (NoiseStyle style : entry.getValue()) {
                    items.add(new ListItem(formatName(style.name()), style.name(), false, () -> {
                        nv.cng = style.create(RNG.r.nextParallelRNG(RNG.r.imax()));
                        nv.generator = null;
                        nv.loader = null;
                        nv.currentName = style.name();
                    }));
                }
                categories.put(category, items);
            }
        }

        return categories;
    }

    private static NoiseCategory categorize(NoiseStyle style) {
        String n = style.name();
        if (n.startsWith("STATIC")) return NoiseCategory.STATIC;
        if (n.startsWith("IRIS")) return NoiseCategory.IRIS;
        if (n.startsWith("CLOVER")) return NoiseCategory.CLOVER;
        if (n.startsWith("VASCULAR")) return NoiseCategory.VASCULAR;
        if (n.equals("FLAT")) return NoiseCategory.UTILITY;
        if (n.startsWith("CELLULAR")) return NoiseCategory.CELLULAR;
        if (n.startsWith("HEX") || n.equals("HEXAGON")) return NoiseCategory.HEXAGON;
        if (n.startsWith("SIERPINSKI")) return NoiseCategory.SIERPINSKI;
        if (n.startsWith("NOWHERE")) return NoiseCategory.NOWHERE;
        if (n.startsWith("GLOB")) return NoiseCategory.GLOBE;
        if (n.startsWith("PERLIN")) return NoiseCategory.PERLIN;
        if (n.startsWith("CUBIC") || (n.startsWith("FRACTAL") && n.contains("CUBIC"))) return NoiseCategory.CUBIC;
        if (n.contains("SIMPLEX") && !n.startsWith("FRACTAL")) return NoiseCategory.SIMPLEX;
        if (n.startsWith("FRACTAL")) return NoiseCategory.FRACTAL;
        return NoiseCategory.OTHER;
    }

    private static String categoryLabel(NoiseCategory category) {
        return IrisLanguage.plain(switch (category) {
            case PACK_GENERATORS -> DesktopUiMessages.NOISE_CATEGORY_PACK_GENERATORS;
            case SIMPLEX -> DesktopUiMessages.NOISE_CATEGORY_SIMPLEX;
            case PERLIN -> DesktopUiMessages.NOISE_CATEGORY_PERLIN;
            case CELLULAR -> DesktopUiMessages.NOISE_CATEGORY_CELLULAR;
            case IRIS -> DesktopUiMessages.NOISE_CATEGORY_IRIS;
            case CLOVER -> DesktopUiMessages.NOISE_CATEGORY_CLOVER;
            case HEXAGON -> DesktopUiMessages.NOISE_CATEGORY_HEXAGON;
            case VASCULAR -> DesktopUiMessages.NOISE_CATEGORY_VASCULAR;
            case GLOBE -> DesktopUiMessages.NOISE_CATEGORY_GLOBE;
            case CUBIC -> DesktopUiMessages.NOISE_CATEGORY_CUBIC;
            case FRACTAL -> DesktopUiMessages.NOISE_CATEGORY_FRACTAL;
            case STATIC -> DesktopUiMessages.NOISE_CATEGORY_STATIC;
            case NOWHERE -> DesktopUiMessages.NOISE_CATEGORY_NOWHERE;
            case SIERPINSKI -> DesktopUiMessages.NOISE_CATEGORY_SIERPINSKI;
            case UTILITY -> DesktopUiMessages.NOISE_CATEGORY_UTILITY;
            case OTHER -> DesktopUiMessages.NOISE_CATEGORY_OTHER;
        });
    }

    private static String formatName(String enumName) {
        String lower = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void refreshGenerator() {
        if (generator != null && loader != null) {
            generator = loader.get();
        }
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        int notches = e.getWheelRotation();
        if (e.isControlDown()) {
            time = time + ((0.0025 * time) * notches);
            return;
        }
        scale = scale + ((0.044 * scale) * notches);
        scale = Math.max(scale, 0.00001);
    }

    private double lerp(double current, double target, double speed) {
        double diff = target - current;
        if (Math.abs(diff) < 0.001) return target;
        return current + diff * speed;
    }

    @Override
    public void paint(Graphics g) {
        animScale = lerp(animScale, scale, 0.16);
        animTime = lerp(animTime, time, 0.29);
        animOx = lerp(animOx, ox, 0.16);
        animOz = lerp(animOz, oz, 0.16);

        PrecisionStopwatch p = PrecisionStopwatch.start();

        if (g instanceof Graphics2D gg) {
            gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int pw = getWidth();
            int ph = getHeight();

            if (pw != imgWidth || ph != imgHeight || img == null) {
                imgWidth = pw;
                imgHeight = ph;
                img = null;
            }

            int accuracy = M.clip((fpsHistory.getAverage() / 14D), 1D, 64D).intValue();
            int rw = Math.max(1, pw / accuracy);
            int rh = Math.max(1, ph / accuracy);

            if (img == null || img.getWidth() != rw || img.getHeight() != rh) {
                img = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
            }

            int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();

            BurstExecutor burst = gx.burst(rw);
            for (int x = 0; x < rw; x++) {
                int xx = x;
                burst.queue(() -> {
                    for (int z = 0; z < rh; z++) {
                        double worldX = (xx * accuracy * animScale) + animOx;
                        double worldZ = (z * accuracy * animScale) + animOz;
                        double n = generator != null
                                ? generator.apply(worldX, worldZ)
                                : cng.noise(worldX, worldZ);
                        n = Math.max(0, Math.min(1, n));

                        int rgb;
                        if (colorMode) {
                            rgb = HSB_LUT[(int) (n * 255)];
                        } else {
                            int v = (int) (n * 255);
                            rgb = (v << 16) | (v << 8) | v;
                        }
                        pixels[z * rw + xx] = rgb;
                    }
                });
            }
            burst.complete();

            gg.setColor(BG);
            gg.fillRect(0, 0, pw, ph);
            gg.drawImage(img, 0, 0, pw, ph, null);

            renderStatusBar(gg, pw, ph, p.getMilliseconds());
            renderCrosshair(gg, pw, ph);
        }

        p.end();
        time += 1D;
        fpsHistory.put(p.getMilliseconds());

        if (!isVisible() || !getParent().isVisible()) {
            return;
        }

        long sleepMs = Math.max(1, 16 - (long) p.getMilliseconds());
        // Pace on a worker, not the EDT: a sleep queued on the event thread blocks painting
        // and input for the whole frame budget. repaint() marshals itself back.
        J.a(() -> {
            J.sleep(sleepMs);
            repaint();
        });
    }

    private void renderCrosshair(Graphics2D g, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;
        g.setColor(new Color(255, 255, 255, 40));
        g.drawLine(cx - 8, cy, cx + 8, cy);
        g.drawLine(cx, cy - 8, cx, cy + 8);
    }

    private void renderStatusBar(Graphics2D g, int w, int h, double frameMs) {
        int barHeight = 28;
        int y = h - barHeight;

        g.setColor(STATUS_BG);
        g.fillRect(0, y, w, barHeight);
        g.setColor(new Color(50, 50, 60));
        g.drawLine(0, y, w, y);

        g.setFont(STATUS_FONT);
        g.setColor(STATUS_TEXT);

        double worldX = (w / 2.0 * animScale) + animOx;
        double worldZ = (h / 2.0 * animScale) + animOz;
        double noiseVal = generator != null
                ? generator.apply(worldX, worldZ)
                : cng.noise(worldX, worldZ);
        noiseVal = Math.max(0, Math.min(1, noiseVal));

        int fps = frameMs > 0 ? (int) (1000.0 / frameMs) : 0;

        String status = IrisLanguage.plain(
                DesktopUiMessages.NOISE_STATUS,
                MessageArgument.untrusted("name", currentName),
                MessageArgument.trusted("x", String.format("%.1f", worldX)),
                MessageArgument.trusted("z", String.format("%.1f", worldZ)),
                MessageArgument.trusted("zoom", String.format("%.4f", animScale)),
                MessageArgument.trusted("value", String.format("%.4f", noiseVal)),
                MessageArgument.trusted("fps", fps)
        );
        g.drawString(status, 8, y + 18);

        int barW = 60;
        int barX = w - barW - 12;
        int barY = y + 6;
        int barH = barHeight - 12;
        g.setColor(new Color(40, 40, 48));
        g.fillRoundRect(barX, barY, barW, barH, 4, 4);
        int fillW = (int) (noiseVal * (barW - 2));
        g.setColor(ACCENT);
        g.fillRoundRect(barX + 1, barY + 1, fillW, barH - 2, 3, 3);
    }

    private static final class ListItem {
        final String text;
        final String rawName;
        final boolean header;
        final Runnable action;

        ListItem(String text, String rawName, boolean header, Runnable action) {
            this.text = text;
            this.rawName = rawName;
            this.header = header;
            this.action = action;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static final class SidebarCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            ListItem item = (ListItem) value;
            super.getListCellRendererComponent(list, item.text, index, !item.header && selected, false);
            setOpaque(true);
            if (item.header) {
                setFont(SIDEBAR_HEADER_FONT);
                setForeground(ACCENT);
                setBackground(SIDEBAR_BG);
                setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
            } else {
                setFont(SIDEBAR_ITEM_FONT);
                setForeground(selected ? Color.WHITE : SIDEBAR_ITEM_COLOR);
                setBackground(selected ? SIDEBAR_SELECTED : SIDEBAR_BG);
                setBorder(BorderFactory.createEmptyBorder(3, 20, 3, 10));
            }
            return this;
        }
    }

    private enum NoiseCategory {
        PACK_GENERATORS,
        SIMPLEX,
        PERLIN,
        CELLULAR,
        IRIS,
        CLOVER,
        HEXAGON,
        VASCULAR,
        GLOBE,
        CUBIC,
        FRACTAL,
        STATIC,
        NOWHERE,
        SIERPINSKI,
        UTILITY,
        OTHER
    }
}
