package art.arcane.iris.core.service;

import art.arcane.iris.core.runtime.jigsaw.JigsawStudioCellDimensions;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioCompatibilityTarget;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioMode;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioPieceRules;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioToolAction;
import art.arcane.iris.core.runtime.jigsaw.JigsawStudioToolPayload;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class JigsawStudioMenuController {
    static final int VARIANTS_PER_PAGE = 28;
    static final int MEMBERSHIPS_PER_PAGE = 28;
    static final int THEME_SETS_PER_PAGE = 21;
    static final int TOOLS_PER_PAGE = 28;
    static final int CHANCE_STEP_PERCENTAGE_POINTS = 5;
    static final int RULE_SHIFT_STEP = 5;
    static final int PLACEMENT_RULE_SHIFT_STEP = 16;

    private static final long DESTRUCTIVE_CONFIRM_NANOS = 10_000_000_000L;
    private static final int[] GRID_POSITIONS = {-3, -2, -1, 0, 1, 2, 3};

    private final JavaPlugin plugin;
    private final Actions actions;
    private final Map<UUID, UIWindow> windows = new ConcurrentHashMap<>();
    private final Map<UUID, PendingUnlink> pendingUnlinks = new ConcurrentHashMap<>();
    private final Map<UUID, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();
    private final Map<UUID, PendingProjectDelete> pendingProjectDeletes = new ConcurrentHashMap<>();

    public JigsawStudioMenuController(JavaPlugin plugin, Actions actions) {
        this.plugin = Objects.requireNonNull(plugin, "Jigsaw Studio menu plugin");
        this.actions = Objects.requireNonNull(actions, "Jigsaw Studio menu actions");
    }

    public boolean open(Player player) {
        if (player == null) {
            return false;
        }
        if (!J.isOwnedByCurrentRegion(player)) {
            return J.runEntity(player, () -> open(player));
        }

        Optional<JigsawStudioMenuState> available = actions.menuState(player);
        if (available.isEmpty()) {
            close(player);
            player.sendMessage(ChatColor.RED + "Iris Jigsaw Studio is not active in this world.");
            return false;
        }
        JigsawStudioMenuState state = available.get();
        if (state.workcells().isEmpty()) {
            close(player);
            player.sendMessage(ChatColor.RED + "This Jigsaw Studio has no workcells.");
            return false;
        }

        JigsawStudioMenuState.Workcell selected = state.selectedWorkcell();
        if (selected == null) {
            String firstWorkcellId = state.workcells().getFirst().stableId();
            if (!actions.selectWorkcell(player, firstWorkcellId)) {
                return false;
            }
            Optional<JigsawStudioMenuState> selectedState = matchingState(player, state.requestId(), false);
            if (selectedState.isEmpty()) {
                return false;
            }
            state = selectedState.get();
            selected = state.selectedWorkcell();
            if (selected == null) {
                return false;
            }
        }

        close(player);
        UUID playerId = player.getUniqueId();
        UIWindow window = new UIWindow(plugin, player);
        window.setResolution(WindowResolution.W9_H6);
        window.setViewportHeight(6);
        window.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
        window.setTitle(title(state.structureKey()));
        window.onClosed(closed -> {
            windows.remove(playerId, window);
            pendingUnlinks.remove(playerId);
            pendingDeletes.remove(playerId);
            pendingProjectDeletes.remove(playerId);
        });
        windows.put(playerId, window);
        pendingUnlinks.remove(playerId);
        pendingDeletes.remove(playerId);
        pendingProjectDeletes.remove(playerId);
        renderMain(window, state, selected, 0);
        window.open();
        return true;
    }

    public boolean openWorkcellSettings(Player player, String workcellId) {
        if (player == null || workcellId == null || workcellId.isBlank()) {
            return false;
        }
        if (!J.isOwnedByCurrentRegion(player)) {
            return J.runEntity(player, () -> openWorkcellSettings(player, workcellId));
        }
        if (!open(player)) {
            return false;
        }
        Optional<JigsawStudioMenuState> current = actions.menuState(player);
        if (current.isEmpty() || current.get().workcell(workcellId) == null) {
            stale(player);
            return false;
        }
        openWorkcellSettings(player, current.get().requestId(), workcellId);
        return true;
    }

    public boolean openVariantSettings(Player player, String workcellId, String pieceKey) {
        if (player == null
                || workcellId == null
                || workcellId.isBlank()
                || pieceKey == null
                || pieceKey.isBlank()) {
            return false;
        }
        if (!J.isOwnedByCurrentRegion(player)) {
            return J.runEntity(player, () -> openVariantSettings(player, workcellId, pieceKey));
        }
        if (!open(player)) {
            return false;
        }
        Optional<JigsawStudioMenuState> current = actions.menuState(player);
        if (current.isEmpty()) {
            stale(player);
            return false;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        if (variant == null || !variant.active() || !variant.owned()) {
            stale(player);
            return false;
        }
        if (!current.get().irisExtended()) {
            player.sendMessage(ChatColor.YELLOW
                    + "Vanilla-portable pieces cannot encode Iris theme or piece-rule metadata.");
            close(player);
            return false;
        }
        openVariantSettings(player, current.get().requestId(), workcellId, pieceKey, 0);
        return true;
    }

    public boolean openVariantSizeSettings(Player player, String workcellId, String pieceKey) {
        if (player == null || workcellId == null || workcellId.isBlank()
                || pieceKey == null || pieceKey.isBlank()) {
            return false;
        }
        if (!J.isOwnedByCurrentRegion(player)) {
            return J.runEntity(player, () -> openVariantSizeSettings(player, workcellId, pieceKey));
        }
        if (!open(player)) {
            return false;
        }
        Optional<JigsawStudioMenuState> current = actions.menuState(player);
        if (current.isEmpty()) {
            stale(player);
            return false;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        if (workcell == null || variant == null || !variant.owned() || variant.dimensions().isEmpty()) {
            stale(player);
            return false;
        }
        UIWindow window = windows.get(player.getUniqueId());
        if (window == null) {
            return false;
        }
        renderVariantSizeSettings(window, current.get(), workcell, variant);
        return true;
    }

    public boolean openStructureSettings(Player player) {
        if (player == null) {
            return false;
        }
        if (!J.isOwnedByCurrentRegion(player)) {
            return J.runEntity(player, () -> openStructureSettings(player));
        }
        if (!open(player)) {
            return false;
        }
        Optional<JigsawStudioMenuState> current = actions.menuState(player);
        if (current.isEmpty() || current.get().selectedWorkcell() == null) {
            stale(player);
            return false;
        }
        openStructureSettings(
                player,
                current.get().requestId(),
                current.get().selectedWorkcellId(),
                0);
        return true;
    }

    public void close(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        UIWindow window = windows.remove(playerId);
        pendingUnlinks.remove(playerId);
        pendingDeletes.remove(playerId);
        pendingProjectDeletes.remove(playerId);
        if (window == null) {
            return;
        }
        if (J.isOwnedByCurrentRegion(player)) {
            window.close();
            return;
        }
        J.runEntity(player, window::close);
    }

    public void closeAll() {
        List<UIWindow> activeWindows = new ArrayList<>(windows.values());
        windows.clear();
        pendingUnlinks.clear();
        pendingDeletes.clear();
        pendingProjectDeletes.clear();
        for (UIWindow window : activeWindows) {
            Player player = window.getViewer();
            if (J.isOwnedByCurrentRegion(player)) {
                window.close();
            } else {
                J.runEntity(player, window::close);
            }
        }
    }

    private void renderMain(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell selected,
            int requestedPage
    ) {
        int page = clampPage(requestedPage, selected.variants().size(), VARIANTS_PER_PAGE);
        window.batch(() -> {
            window.clearElements();
            addWorkcells(window, state, selected.stableId());
            addFamilyControl(window, state);
            addStructureControl(window, state, selected.stableId());
            addVariants(window, state, selected, page);
            addMainActions(window, state, selected, page);
        });
    }

    private void addFamilyControl(UIWindow window, JigsawStudioMenuState state) {
        String nextThemeKey = nextThemeSetKey(state.themeSets());
        boolean available = state.irisExtended() && state.workcells().stream()
                .filter(JigsawStudioMenuState.Workcell::enabled)
                .allMatch(workcell -> workcell.activeVariant() != null && workcell.activeVariant().owned());
        UIElement family = element(
                "duplicate-family",
                available ? Material.PURPLE_DYE : Material.GRAY_DYE,
                available
                        ? ChatColor.LIGHT_PURPLE + "Duplicate All Enabled Cells as Family"
                        : ChatColor.GRAY + "Duplicate All Enabled Cells as Family");
        family.addLore(ChatColor.GRAY + "Copies each enabled cell's loaded variant into one coherent family.");
        family.addLore(ChatColor.GRAY + "New family: " + safe(nextThemeKey));
        if (available) {
            family.addLore(ChatColor.YELLOW + "Left-click to duplicate the complete family atomically");
            family.onLeftClick(clicked -> duplicateActiveFamily(
                    window.getViewer(), state.requestId(), nextThemeKey));
        } else if (!state.irisExtended()) {
            family.addLore(ChatColor.GRAY + "Coherent families require Iris compatibility.");
        } else {
            family.addLore(ChatColor.GRAY + "Load an owned variant in every enabled workcell first.");
        }
        window.setElement(3, 0, family);
    }

    private void addStructureControl(
            UIWindow window,
            JigsawStudioMenuState state,
            String selectedWorkcellId
    ) {
        UIElement structure = element(
                "structure-rules",
                state.irisExtended()
                        ? state.requireCaps() ? Material.IRON_BARS : Material.TRIPWIRE_HOOK
                        : Material.BARRIER,
                ChatColor.LIGHT_PURPLE + "Structure Rules");
        structure.addLore(ChatColor.GRAY + "Mandatory caps: " + yesNo(state.requireCaps()));
        structure.addLore(ChatColor.GRAY + "Theme sets: " + state.themeSets().size());
        structure.addLore(ChatColor.GRAY + "Compatibility: " + compatibilityName(state.compatibilityTarget()));
        structure.addLore(ChatColor.YELLOW + "Left-click for themes and rules");
        structure.addLore(state.irisExtended()
                ? ChatColor.YELLOW + "Right-click to toggle mandatory caps"
                : ChatColor.GRAY + "Mandatory caps and themes require Iris compatibility");
        structure.onLeftClick(clicked -> openStructureSettings(
                window.getViewer(), state.requestId(), selectedWorkcellId, 0));
        if (state.irisExtended()) {
            structure.onRightClick(clicked -> setRequireCaps(
                    window.getViewer(), state.requestId(), !state.requireCaps()));
        }
        window.setElement(4, 0, structure);
    }

    private void addWorkcells(UIWindow window, JigsawStudioMenuState state, String selectedWorkcellId) {
        int count = state.workcells().size();
        int start = count == 1 ? 0 : -(count / 2);
        for (int index = 0; index < count; index++) {
            JigsawStudioMenuState.Workcell workcell = state.workcells().get(index);
            boolean selected = workcell.stableId().equals(selectedWorkcellId);
            UIElement element = element(
                    "workcell-" + index,
                    workcellMaterial(workcell, selected),
                    (selected ? ChatColor.AQUA : workcell.enabled() ? ChatColor.WHITE : ChatColor.GRAY)
                            + safe(workcell.displayName()))
                    .setEnchanted(selected);
            element.addLore(workcell.enabled()
                    ? ChatColor.GREEN + "Enabled"
                    : ChatColor.RED + "Disabled for assembly and export");
            if (!workcell.canonicalName().equals(workcell.displayName())) {
                element.addLore(ChatColor.GRAY + "Solver role: " + safe(workcell.canonicalName()));
            }
            element.addLore(ChatColor.GRAY + "Capacity: " + dimensions(workcell.capacity()));
            element.addLore(ChatColor.GRAY + "Variants: " + workcell.variants().size());
            JigsawStudioMenuState.Variant active = workcell.activeVariant();
            element.addLore(ChatColor.GRAY + "Loaded: "
                    + (active == null ? "None" : safe(active.displayName())));
            element.addLore(workcellStatus(workcell));
            element.addLore(ChatColor.YELLOW + "Left-click to select");
            element.addLore(ChatColor.YELLOW + "Right-click for workcell settings");
            if (workcell.dirty() && !workcell.saving()) {
                element.addLore(ChatColor.GOLD + "Shift-left: Flush Autosave Now");
                element.onShiftLeftClick(clicked -> flushNow(
                        window.getViewer(), state.requestId(), workcell.stableId()));
            }
            element.onLeftClick(clicked -> selectWorkcell(
                    window.getViewer(),
                    state.requestId(),
                    workcell.stableId()));
            element.onRightClick(clicked -> J.runEntity(
                    window.getViewer(),
                    () -> openWorkcellSettings(
                            window.getViewer(),
                            state.requestId(),
                            workcell.stableId()),
                    1));
            window.setElement(start + index, 0, element);
        }
    }

    private void addVariants(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            int page
    ) {
        List<JigsawStudioMenuState.Variant> variants = page(
                workcell.variants(), page, VARIANTS_PER_PAGE);
        if (variants.isEmpty()) {
            UIElement empty = element("no-variants", Material.BARRIER, ChatColor.RED + "No variants");
            empty.addLore(ChatColor.GRAY + "Create a variant to begin authoring this workcell.");
            window.setElement(0, 2, empty);
            return;
        }

        for (int index = 0; index < variants.size(); index++) {
            JigsawStudioMenuState.Variant variant = variants.get(index);
            int position = GRID_POSITIONS[index % GRID_POSITIONS.length];
            int row = 1 + index / GRID_POSITIONS.length;
            UIElement element = element(
                    "variant-" + index,
                    variantMaterial(variant),
                    (variant.active() ? ChatColor.GREEN : ChatColor.WHITE)
                            + safe(variant.displayName())
                            + (variant.active() ? ChatColor.AQUA + " [Loaded]" : ""))
                    .setEnchanted(variant.active());
            element.addLore(ChatColor.DARK_GRAY + safe(variant.pieceKey()));
            element.addLore(variant.owned()
                    ? ChatColor.GREEN + "Owned"
                    : ChatColor.GRAY + "Read-only");
            element.addLore(ChatColor.GRAY + "Rotation: " + (variant.rotatable() ? "Enabled" : "Disabled"));
            element.addLore(ChatColor.GRAY + "Size: " + variant.dimensions()
                    .map(JigsawStudioMenuController::dimensions)
                    .orElse("Object missing"));
            element.addLore(ChatColor.GRAY + "Themes: " + themes(variant.themes()));
            element.addLore(ChatColor.GRAY + "Pool entries: " + variant.memberships().size());
            element.addLore(variant.active()
                    ? ChatColor.YELLOW + "Loaded; right-click for details"
                    : ChatColor.YELLOW + "Left-click to load");
            if (!variant.active()) {
                element.addLore(ChatColor.YELLOW + "Right-click for details or deletion");
            }
            element.onLeftClick(clicked -> switchVariant(
                    window.getViewer(),
                    state.requestId(),
                    workcell.stableId(),
                    variant.pieceKey()));
            element.onRightClick(clicked -> J.runEntity(
                    window.getViewer(),
                    () -> openDetails(
                            window.getViewer(),
                            state.requestId(),
                            workcell.stableId(),
                            variant.pieceKey(),
                            0),
                    1));
            window.setElement(position, row, element);
        }
    }

    private void addMainActions(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            int page
    ) {
        UIElement create = element("create", Material.NETHER_STAR, ChatColor.GREEN + "New Blank Variant");
        create.addLore(ChatColor.GRAY + "Create an empty owned variant with this cell's current size.");
        create.onLeftClick(clicked -> createVariant(
                window.getViewer(), state.requestId(), workcell.stableId(), false));
        window.setElement(-4, 5, create);

        JigsawStudioMenuState.Variant active = workcell.activeVariant();
        boolean duplicateAvailable = active != null && active.owned();
        UIElement duplicate = element(
                "duplicate",
                duplicateAvailable ? Material.PAPER : Material.GRAY_DYE,
                duplicateAvailable
                        ? ChatColor.AQUA + "Duplicate This Cell's Variant"
                        : active == null
                        ? ChatColor.GRAY + "Duplicate This Cell's Variant"
                        : ChatColor.GRAY + "Duplicate This Cell's Variant");
        duplicate.addLore(duplicateAvailable
                ? ChatColor.GRAY + "Clone the loaded variant into an owned variant."
                : active == null
                        ? ChatColor.GRAY + "Load a variant before duplicating it."
                        : ChatColor.GRAY + "Adopt the read-only graph before duplicating it.");
        if (duplicateAvailable) {
            duplicate.onLeftClick(clicked -> createVariant(
                    window.getViewer(), state.requestId(), workcell.stableId(), true));
        }
        window.setElement(-3, 5, duplicate);

        UIElement settings = element("settings", Material.CRAFTING_TABLE, ChatColor.GOLD + "Workcell Settings");
        settings.addLore(ChatColor.GRAY + "Enabled: " + yesNo(workcell.enabled()));
        settings.addLore(ChatColor.GRAY + "Capacity: " + dimensions(workcell.capacity()));
        settings.onLeftClick(clicked -> openWorkcellSettings(
                window.getViewer(), state.requestId(), workcell.stableId()));
        window.setElement(-2, 5, settings);

        int pageCount = pageCount(workcell.variants().size(), VARIANTS_PER_PAGE);
        if (page > 0) {
            UIElement previous = element("previous", Material.ARROW, ChatColor.YELLOW + "Previous Page");
            previous.onLeftClick(clicked -> refreshMain(
                    window.getViewer(), state.requestId(), workcell.stableId(), page - 1));
            window.setElement(-1, 5, previous);
        }

        UIElement pageIndicator = element("page", Material.BOOK, ChatColor.WHITE + "Page "
                + (page + 1) + "/" + pageCount);
        pageIndicator.addLore(ChatColor.GRAY + "" + workcell.variants().size() + " variants");
        window.setElement(0, 5, pageIndicator);

        if (page + 1 < pageCount) {
            UIElement next = element("next", Material.ARROW, ChatColor.YELLOW + "Next Page");
            next.onLeftClick(clicked -> refreshMain(
                    window.getViewer(), state.requestId(), workcell.stableId(), page + 1));
            window.setElement(1, 5, next);
        }

        window.setElement(2, 5, evaluationElement(state.evaluation()));

        UIElement preview = element("preview", Material.ENDER_EYE, ChatColor.LIGHT_PURPLE + "Go to Preview");
        preview.addLore(ChatColor.GRAY + "Open the deterministic assembly preview.");
        preview.onLeftClick(clicked -> goToPreview(window.getViewer(), state.requestId()));
        window.setElement(3, 5, preview);

        UIElement toolbox = element("toolbox", Material.STICK, ChatColor.AQUA + "Toolbox");
        toolbox.addLore(ChatColor.GRAY + "Take named sticks bound to this Studio session.");
        toolbox.onLeftClick(clicked -> openToolbox(
                window.getViewer(), state.requestId(), workcell.stableId(), 0));
        window.setElement(4, 5, toolbox);
    }

    private void renderWorkcellSettings(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell
    ) {
        window.batch(() -> {
            window.clearElements();

            UIElement back = element("settings-back", Material.ARROW, ChatColor.YELLOW + "Back to Variants");
            back.onLeftClick(clicked -> refreshMain(
                    window.getViewer(), state.requestId(), workcell.stableId(), 0));
            window.setElement(-4, 0, back);

            UIElement identity = element(
                    "settings-identity",
                    workcell.enabled() ? Material.LIME_WOOL : Material.GRAY_WOOL,
                    ChatColor.AQUA + safe(workcell.displayName()));
            identity.addLore(ChatColor.DARK_GRAY + safe(workcell.stableId()));
            identity.addLore(workcell.enabled()
                    ? ChatColor.GREEN + "Enabled"
                    : ChatColor.RED + "Disabled for assembly and export");
            if (!workcell.canonicalName().equals(workcell.displayName())) {
                identity.addLore(ChatColor.GRAY + "Solver role: " + safe(workcell.canonicalName()));
            }
            identity.addLore(ChatColor.GRAY + "Capacity: " + dimensions(workcell.capacity()));
            identity.addLore(ChatColor.YELLOW + "Left-click for a rename stick");
            identity.addLore(ChatColor.GRAY + "Rename that stick in an anvil, then right-click it.");
            identity.addLore(ChatColor.GRAY + "Sneak-right-click the stick to reset this label.");
            identity.onLeftClick(clicked -> actions.giveTool(
                    window.getViewer(),
                    JigsawStudioToolPayload.workcell(
                            JigsawStudioToolAction.RENAME_WORKCELL,
                            state.requestId(),
                            workcell.stableId())));
            window.setElement(0, 0, identity);
            window.setElement(4, 0, evaluationElement(state.evaluation()));

            if (state.mode() == JigsawStudioMode.PLANAR_JIGSAW) {
                UIElement enabled = element(
                        "settings-enabled",
                        workcell.enabled() ? Material.LEVER : Material.REDSTONE_TORCH,
                        workcell.enabled()
                                ? ChatColor.GREEN + "Workcell Enabled"
                                : ChatColor.RED + "Workcell Disabled");
                enabled.addLore(ChatColor.GRAY + "Disabled workcells remain editable and keep their size.");
                enabled.addLore(ChatColor.YELLOW + "Left-click to "
                        + (workcell.enabled() ? "disable" : "enable"));
                enabled.onLeftClick(clicked -> setWorkcellEnabled(
                        window.getViewer(),
                        state.requestId(),
                        workcell.stableId(),
                        !workcell.enabled()));
                window.setElement(0, 1, enabled);
            } else {
                UIElement spatial = element(
                        "spatial-bounds",
                        Material.SCAFFOLDING,
                        ChatColor.AQUA + "Spatial Workcell Bounds");
                spatial.addLore(ChatColor.GRAY + "Spatial project bounds are shared by the project.");
                spatial.addLore(ChatColor.GRAY + "Changing them regenerates the Studio layout.");
                window.setElement(0, 1, spatial);
            }

            window.setElement(-2, 2, axisElement(
                    window,
                    state,
                    workcell,
                    DimensionAxis.WIDTH,
                    Material.IRON_INGOT));
            window.setElement(0, 2, axisElement(
                    window,
                    state,
                    workcell,
                    DimensionAxis.HEIGHT,
                    Material.GOLD_INGOT));
            window.setElement(2, 2, axisElement(
                    window,
                    state,
                    workcell,
                    DimensionAxis.DEPTH,
                    Material.COPPER_INGOT));

            UIElement footerBack = element("settings-footer-back", Material.ARROW, ChatColor.YELLOW + "Back");
            footerBack.onLeftClick(clicked -> refreshMain(
                    window.getViewer(), state.requestId(), workcell.stableId(), 0));
            window.setElement(-4, 5, footerBack);

            if (workcell.dirty() && !workcell.saving()) {
                UIElement saveNow = element(
                        "save-now",
                        Material.EMERALD,
                        ChatColor.GOLD + "Flush Autosave Now");
                saveNow.addLore(ChatColor.GRAY + "Autosave is automatic.");
                saveNow.addLore(ChatColor.GRAY + "Use this only to flush pending work or recover immediately.");
                saveNow.onLeftClick(clicked -> flushNow(
                        window.getViewer(), state.requestId(), workcell.stableId()));
                window.setElement(2, 5, saveNow);
            }

            UIElement preview = element(
                    "settings-preview",
                    Material.ENDER_EYE,
                    ChatColor.LIGHT_PURPLE + "Go to Preview");
            preview.onLeftClick(clicked -> goToPreview(window.getViewer(), state.requestId()));
            window.setElement(3, 5, preview);

            UIElement toolbox = element("settings-toolbox", Material.STICK, ChatColor.AQUA + "Toolbox");
            toolbox.onLeftClick(clicked -> openToolbox(
                    window.getViewer(), state.requestId(), workcell.stableId(), 0));
            window.setElement(4, 5, toolbox);
        });
    }

    private void renderVariantSizeSettings(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant
    ) {
        JigsawStudioCellDimensions current = variant.dimensions().orElseThrow();
        window.batch(() -> {
            window.clearElements();

            UIElement back = element("variant-size-back", Material.ARROW, ChatColor.YELLOW + "Back to Details");
            back.onLeftClick(clicked -> openDetails(
                    window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), 0));
            window.setElement(-4, 0, back);

            UIElement identity = element(
                    "variant-size-identity",
                    Material.SCAFFOLDING,
                    ChatColor.AQUA + safe(variant.displayName()) + " Size");
            identity.addLore(ChatColor.DARK_GRAY + safe(variant.pieceKey()));
            identity.addLore(ChatColor.WHITE + "Current: " + dimensions(current));
            identity.addLore(ChatColor.GRAY + "Workcell capacity: " + dimensions(workcell.capacity()));
            identity.addLore(ChatColor.GRAY + "Only this variant object and its edge connectors change.");
            window.setElement(0, 0, identity);
            window.setElement(4, 0, evaluationElement(state.evaluation()));

            window.setElement(-2, 2, variantAxisElement(
                    window, state, workcell, variant, DimensionAxis.WIDTH, Material.IRON_INGOT));
            window.setElement(0, 2, variantAxisElement(
                    window, state, workcell, variant, DimensionAxis.HEIGHT, Material.GOLD_INGOT));
            window.setElement(2, 2, variantAxisElement(
                    window, state, workcell, variant, DimensionAxis.DEPTH, Material.COPPER_INGOT));

            UIElement capacity = element(
                    "variant-size-capacity",
                    variant.resizableToCapacity() ? Material.NETHER_STAR : Material.GRAY_DYE,
                    variant.resizableToCapacity()
                            ? ChatColor.GREEN + "Resize This Variant to Capacity"
                            : ChatColor.GRAY + "Variant Already Matches Capacity");
            capacity.addLore(ChatColor.GRAY + dimensions(workcell.capacity()));
            if (variant.resizableToCapacity()) {
                capacity.addLore(ChatColor.YELLOW + "Left-click to resize only this variant");
                capacity.onLeftClick(clicked -> resizeVariant(
                        window.getViewer(),
                        state.requestId(),
                        workcell.stableId(),
                        variant.pieceKey(),
                        workcell.capacity()));
            }
            window.setElement(0, 3, capacity);

            UIElement footerBack = element("variant-size-footer-back", Material.ARROW, ChatColor.YELLOW + "Back");
            footerBack.onLeftClick(clicked -> openDetails(
                    window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), 0));
            window.setElement(-4, 5, footerBack);
        });
    }

    private UIElement variantAxisElement(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            DimensionAxis axis,
            Material material
    ) {
        JigsawStudioCellDimensions current = variant.dimensions().orElseThrow();
        UIElement element = element(
                "variant-axis-" + axis.name().toLowerCase(Locale.ROOT),
                material,
                ChatColor.AQUA + axis.displayName() + ": " + axis.value(current));
        element.addLore(ChatColor.GREEN + "Left-click: +1");
        element.addLore(ChatColor.YELLOW + "Right-click: -1");
        element.addLore(ChatColor.GREEN + "Shift-left: +8");
        element.addLore(ChatColor.YELLOW + "Shift-right: -8");
        element.addLore(ChatColor.GRAY + "Maximum: " + axis.value(workcell.capacity()));
        element.onLeftClick(clicked -> resizeVariantAxis(
                window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), axis, 1));
        element.onRightClick(clicked -> resizeVariantAxis(
                window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), axis, -1));
        element.onShiftLeftClick(clicked -> resizeVariantAxis(
                window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), axis, 8));
        element.onShiftRightClick(clicked -> resizeVariantAxis(
                window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), axis, -8));
        return element;
    }

    private void renderStructureSettings(
            UIWindow window,
            JigsawStudioMenuState state,
            String selectedWorkcellId,
            int requestedPage
    ) {
        int page = clampPage(requestedPage, state.themeSets().size(), THEME_SETS_PER_PAGE);
        purgeExpiredConfirmations(window.getViewer().getUniqueId());
        window.batch(() -> {
            window.clearElements();

            UIElement back = element("structure-back", Material.ARROW, ChatColor.YELLOW + "Back to Variants");
            back.onLeftClick(clicked -> refreshMain(
                    window.getViewer(), state.requestId(), selectedWorkcellId, 0));
            window.setElement(-4, 0, back);

            UIElement identity = element(
                    "structure-identity",
                    Material.JIGSAW,
                    ChatColor.LIGHT_PURPLE + "Structure Themes & Caps");
            identity.addLore(ChatColor.DARK_GRAY + safe(state.structureKey()));
            identity.addLore(ChatColor.GRAY + "Compatibility: " + compatibilityName(state.compatibilityTarget()));
            identity.addLore(ChatColor.GRAY + "Theme sets select one coherent variant family per assembly.");
            identity.addLore(ChatColor.GRAY + "Empty piece themes remain available to every family.");
            window.setElement(0, 0, identity);
            window.setElement(4, 0, evaluationElement(state.evaluation()));

            UIElement requireCaps = element(
                    "require-caps",
                    state.irisExtended()
                            ? state.requireCaps() ? Material.IRON_BARS : Material.TRIPWIRE_HOOK
                            : Material.BARRIER,
                    !state.irisExtended()
                            ? ChatColor.GRAY + "Mandatory Caps Require Iris Compatibility"
                            : state.requireCaps()
                            ? ChatColor.GREEN + "Mandatory Caps Enabled"
                            : ChatColor.YELLOW + "Mandatory Caps Disabled");
            requireCaps.addLore(!state.irisExtended()
                    ? ChatColor.GRAY + "Vanilla-portable export rejects Iris mandatory-cap metadata."
                    : state.requireCaps()
                    ? ChatColor.GRAY + "Every open connector must close with a terminal piece."
                    : ChatColor.GRAY + "Assembly may leave an open connector when no cap can fit.");
            if (state.irisExtended()) {
                requireCaps.addLore(ChatColor.YELLOW + "Left-click to toggle");
                requireCaps.onLeftClick(clicked -> setRequireCaps(
                        window.getViewer(), state.requestId(), !state.requireCaps()));
            }
            window.setElement(-3, 1, requireCaps);

            String nextThemeKey = nextThemeSetKey(state.themeSets());
            boolean themeEditingAvailable = state.irisExtended();
            UIElement createTheme = element(
                    "new-theme-set",
                    themeEditingAvailable ? Material.NETHER_STAR : Material.BARRIER,
                    themeEditingAvailable
                            ? ChatColor.GREEN + "Duplicate All Enabled Cells as Family: " + safe(nextThemeKey)
                            : ChatColor.GRAY + "Theme Sets Require Iris Compatibility");
            if (themeEditingAvailable) {
                createTheme.addLore(ChatColor.GRAY + "Creates one new owned variant for every enabled workcell.");
                createTheme.addLore(ChatColor.GRAY + "All created variants join " + safe(nextThemeKey) + ".");
                createTheme.addLore(ChatColor.YELLOW + "Left-click to create the complete set");
                createTheme.onLeftClick(clicked -> duplicateActiveFamily(
                        window.getViewer(), state.requestId(), nextThemeKey));
            } else {
                createTheme.addLore(ChatColor.GRAY + "Vanilla jigsaw resources cannot encode Iris theme metadata.");
            }
            window.setElement(0, 1, createTheme);

            boolean deleteConfirmed = isProjectDeleteConfirmed(
                    window.getViewer().getUniqueId(), state.requestId());
            UIElement deleteProject = element(
                    "delete-project",
                    deleteConfirmed ? Material.REDSTONE : Material.LAVA_BUCKET,
                    deleteConfirmed
                            ? ChatColor.RED + "Confirm Delete Jigsaw"
                            : ChatColor.RED + "Delete Jigsaw")
                    .setEnchanted(deleteConfirmed);
            deleteProject.addLore(deleteConfirmed
                    ? ChatColor.RED + "Click again to delete this entire managed project."
                    : ChatColor.RED + "Click twice within 10 seconds to confirm.");
            deleteProject.onLeftClick(clicked -> confirmOrDeleteProject(
                    window.getViewer(), state.requestId(), selectedWorkcellId, page));
            window.setElement(3, 1, deleteProject);

            addThemeSets(window, state, page);

            UIElement footerBack = element("structure-footer-back", Material.ARROW, ChatColor.YELLOW + "Back");
            footerBack.onLeftClick(clicked -> refreshMain(
                    window.getViewer(), state.requestId(), selectedWorkcellId, 0));
            window.setElement(-4, 5, footerBack);

            int pageCount = pageCount(state.themeSets().size(), THEME_SETS_PER_PAGE);
            if (page > 0) {
                UIElement previous = element(
                        "theme-previous",
                        Material.ARROW,
                        ChatColor.YELLOW + "Previous Page");
                previous.onLeftClick(clicked -> refreshStructureSettings(
                        window.getViewer(), state.requestId(), selectedWorkcellId, page - 1));
                window.setElement(-1, 5, previous);
            }
            UIElement indicator = element(
                    "theme-page",
                    Material.BOOK,
                    ChatColor.WHITE + "Page " + (page + 1) + "/" + pageCount);
            indicator.addLore(ChatColor.GRAY + "" + state.themeSets().size() + " theme sets");
            window.setElement(0, 5, indicator);
            if (page + 1 < pageCount) {
                UIElement next = element("theme-next", Material.ARROW, ChatColor.YELLOW + "Next Page");
                next.onLeftClick(clicked -> refreshStructureSettings(
                        window.getViewer(), state.requestId(), selectedWorkcellId, page + 1));
                window.setElement(1, 5, next);
            }
            window.setElement(4, 5, evaluationElement(state.evaluation()));
        });
    }

    private void addThemeSets(
            UIWindow window,
            JigsawStudioMenuState state,
            int page
    ) {
        List<JigsawStudioMenuState.ThemeSet> themeSets = page(
                state.themeSets(), page, THEME_SETS_PER_PAGE);
        if (themeSets.isEmpty()) {
            UIElement empty = element(
                    "no-theme-sets",
                    Material.GRAY_DYE,
                    ChatColor.GRAY + "Implicit Unthemed Assembly");
            empty.addLore(ChatColor.GRAY + "Create variant-1 to begin coherent theme selection.");
            window.setElement(0, 3, empty);
            return;
        }
        for (int index = 0; index < themeSets.size(); index++) {
            JigsawStudioMenuState.ThemeSet themeSet = themeSets.get(index);
            int position = GRID_POSITIONS[index % GRID_POSITIONS.length];
            int row = 2 + index / GRID_POSITIONS.length;
            UIElement element = element(
                    "theme-set-" + index,
                    state.irisExtended() ? Material.PURPLE_DYE : Material.GRAY_DYE,
                    (state.irisExtended() ? ChatColor.LIGHT_PURPLE : ChatColor.GRAY) + safe(themeSet.key()));
            element.addLore(ChatColor.WHITE + "Selection weight: " + themeSet.weight());
            if (state.irisExtended()) {
                element.addLore(ChatColor.GREEN + "Left-click: weight +1");
                element.addLore(ChatColor.YELLOW + "Right-click: weight -1");
                element.addLore(ChatColor.GREEN + "Shift-left: weight +8");
                element.addLore(ChatColor.YELLOW + "Shift-right: weight -8");
                element.onLeftClick(clicked -> adjustThemeSetWeight(
                        window.getViewer(), state.requestId(), themeSet, 1));
                element.onRightClick(clicked -> adjustThemeSetWeight(
                        window.getViewer(), state.requestId(), themeSet, -1));
                element.onShiftLeftClick(clicked -> adjustThemeSetWeight(
                        window.getViewer(), state.requestId(), themeSet, 8));
                element.onShiftRightClick(clicked -> adjustThemeSetWeight(
                        window.getViewer(), state.requestId(), themeSet, -8));
            } else {
                element.addLore(ChatColor.GRAY + "Theme metadata is unavailable for vanilla-portable graphs.");
            }
            window.setElement(position, row, element);
        }
    }

    private UIElement axisElement(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            DimensionAxis axis,
            Material material
    ) {
        UIElement element = element(
                "axis-" + axis.name().toLowerCase(Locale.ROOT),
                material,
                ChatColor.AQUA + axis.displayName() + " Capacity: " + axis.value(workcell.capacity()));
        element.addLore(ChatColor.GREEN + "Left-click: +1");
        element.addLore(ChatColor.YELLOW + "Right-click: -1");
        element.addLore(ChatColor.GREEN + "Shift-left: +8");
        element.addLore(ChatColor.YELLOW + "Shift-right: -8");
        element.addLore(ChatColor.GRAY + "The Studio layout regenerates after resizing.");
        element.onLeftClick(clicked -> resizeWorkcell(
                window.getViewer(), state.requestId(), workcell.stableId(), axis, 1));
        element.onRightClick(clicked -> resizeWorkcell(
                window.getViewer(), state.requestId(), workcell.stableId(), axis, -1));
        element.onShiftLeftClick(clicked -> resizeWorkcell(
                window.getViewer(), state.requestId(), workcell.stableId(), axis, 8));
        element.onShiftRightClick(clicked -> resizeWorkcell(
                window.getViewer(), state.requestId(), workcell.stableId(), axis, -8));
        return element;
    }

    private void renderDetails(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            int requestedPage
    ) {
        int page = clampPage(requestedPage, variant.memberships().size(), MEMBERSHIPS_PER_PAGE);
        purgeExpiredConfirmations(window.getViewer().getUniqueId());
        window.batch(() -> {
            window.clearElements();
            addDetailsHeader(window, state, workcell, variant, page);
            addMemberships(window, state, workcell, variant, page);
            addDetailsFooter(window, state, workcell, variant, page);
        });
    }

    private void addDetailsHeader(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            int page
    ) {
        UIElement back = element("back", Material.ARROW, ChatColor.YELLOW + "Back to Variants");
        back.onLeftClick(clicked -> refreshMain(
                window.getViewer(), state.requestId(), workcell.stableId(), 0));
        window.setElement(-4, 0, back);

        UIElement identity = element("identity", Material.NAME_TAG, ChatColor.AQUA + safe(variant.displayName()));
        identity.addLore(ChatColor.DARK_GRAY + safe(variant.pieceKey()));
        identity.addLore(variant.owned()
                ? ChatColor.GREEN + "Owned variant"
                : ChatColor.GRAY + "Read-only variant");
        identity.addLore(variant.active()
                ? ChatColor.GREEN + "Loaded in this workcell"
                : ChatColor.GRAY + "Not currently loaded");
        identity.addLore(ChatColor.GRAY + "Size: " + variant.dimensions()
                .map(JigsawStudioMenuController::dimensions)
                .orElse("Object missing"));
        identity.addLore(ChatColor.GRAY + "Themes: " + themes(variant.themes()));
        identity.addLore(ChatColor.GRAY + "Depth: " + variant.rules().minimumDepth()
                + "-" + variant.rules().maximumDepth());
        identity.addLore(ChatColor.GRAY + "Placements: " + variant.rules().minimumPlacements()
                + "-" + maximumPlacements(variant.rules().maximumPlacements()));
        identity.addLore(ChatColor.GRAY + "Terminal: " + yesNo(variant.rules().terminal()));
        identity.addLore(ChatColor.GRAY + "Pool entries: " + variant.memberships().size());
        if (variant.owned()) {
            identity.addLore(ChatColor.YELLOW + "Left-click for a rename stick");
            identity.addLore(ChatColor.GRAY + "Rename that stick in an anvil, then right-click it.");
            identity.addLore(ChatColor.GRAY + "Sneak-right-click the stick to reset this label.");
            identity.onLeftClick(clicked -> actions.giveTool(
                    window.getViewer(),
                    JigsawStudioToolPayload.variant(
                            JigsawStudioToolAction.RENAME_VARIANT,
                            state.requestId(),
                            workcell.stableId(),
                            variant.pieceKey())));
        }
        window.setElement(0, 0, identity);

        boolean activeOwned = variant.active() && variant.owned();
        boolean irisRuleEditing = activeOwned && state.irisExtended();
        UIElement settings = element(
                "variant-settings",
                irisRuleEditing ? Material.COMPARATOR : Material.GRAY_DYE,
                irisRuleEditing
                        ? ChatColor.GOLD + "Themes & Piece Rules"
                        : ChatColor.GRAY + "Themes & Rules Unavailable");
        settings.addLore(irisRuleEditing
                ? ChatColor.GRAY + "Edit this loaded variant's theme membership and placement rules."
                : state.irisExtended()
                        ? ChatColor.GRAY + "Load an owned variant before editing its rules."
                        : ChatColor.GRAY + "Vanilla-portable pieces cannot encode Iris theme or rule metadata.");
        if (irisRuleEditing) {
            settings.addLore(ChatColor.YELLOW + "Left-click to edit");
            settings.onLeftClick(clicked -> openVariantSettings(
                    window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), 0));
        }
        window.setElement(-2, 0, settings);

        boolean rotationEditable = variant.active() && variant.rotationEditable();
        UIElement rotation = element(
                "rotation",
                rotationEditable ? Material.REPEATER : Material.BARRIER,
                rotationEditable
                        ? ChatColor.LIGHT_PURPLE + "Toggle Rotation"
                        : ChatColor.GRAY + "Rotation is Read-only");
        rotation.addLore(ChatColor.GRAY + "Currently: " + (variant.rotatable() ? "Enabled" : "Disabled"));
        if (rotationEditable) {
            rotation.addLore(ChatColor.YELLOW + "Left-click to toggle");
            rotation.onLeftClick(clicked -> toggleRotation(
                    window.getViewer(), state.requestId(), workcell.stableId()));
        } else if (!variant.active()) {
            rotation.addLore(ChatColor.GRAY + "Load this variant before editing rotation.");
        } else if (variant.owned() && variant.rotatable()) {
            rotation.addLore(ChatColor.GRAY + "Vanilla-portable variants must remain rotatable.");
        }
        window.setElement(3, 0, rotation);

        boolean sizeEditable = variant.owned() && variant.dimensions().isPresent();
        UIElement size = element(
                "variant-size",
                sizeEditable ? Material.SCAFFOLDING : Material.BARRIER,
                sizeEditable
                        ? ChatColor.GREEN + "Edit This Variant's Size"
                        : ChatColor.GRAY + "Variant Size is Read-only");
        size.addLore(ChatColor.GRAY + "Current: " + variant.dimensions()
                .map(JigsawStudioMenuController::dimensions)
                .orElse("Object missing"));
        size.addLore(ChatColor.GRAY + "Workcell capacity: " + dimensions(workcell.capacity()));
        if (sizeEditable) {
            size.addLore(ChatColor.YELLOW + "Left-click for width, height, and depth controls");
            size.onLeftClick(clicked -> openVariantSizeSettings(
                    window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey()));
            if (variant.resizableToCapacity()) {
                size.addLore(ChatColor.YELLOW + "Shift-left: resize this variant to capacity");
                size.onShiftLeftClick(clicked -> resizeVariant(
                        window.getViewer(),
                        state.requestId(),
                        workcell.stableId(),
                        variant.pieceKey(),
                        workcell.capacity()));
            }
        }
        window.setElement(2, 0, size);

        boolean lastVariant = workcell.variants().size() <= 1;
        boolean deletionAvailable = variant.owned() && !variant.active() && !lastVariant;
        boolean confirmed = deletionAvailable && isDeleteConfirmed(
                window.getViewer().getUniqueId(),
                state.requestId(),
                workcell.stableId(),
                variant.pieceKey());
        UIElement delete = element(
                "delete-variant",
                deletionAvailable ? confirmed ? Material.REDSTONE : Material.LAVA_BUCKET : Material.BARRIER,
                deletionAvailable
                        ? confirmed ? ChatColor.RED + "Confirm Delete Variant" : ChatColor.RED + "Delete Variant"
                        : variant.active()
                                ? ChatColor.GRAY + "Loaded Variant Cannot Be Deleted"
                                : lastVariant
                                        ? ChatColor.GRAY + "Last Variant Cannot Be Deleted"
                                        : ChatColor.GRAY + "Deletion is Read-only")
                .setEnchanted(confirmed);
        if (deletionAvailable) {
            delete.addLore(confirmed
                    ? ChatColor.RED + "Click again to permanently delete this owned variant."
                    : ChatColor.RED + "Click twice within 10 seconds to confirm.");
            delete.onLeftClick(clicked -> confirmOrDeleteVariant(
                    window.getViewer(),
                    state.requestId(),
                    workcell.stableId(),
                    variant.pieceKey(),
                    page));
        } else if (variant.active()) {
            delete.addLore(ChatColor.GRAY + "Load another variant in this workcell first.");
        } else if (lastVariant) {
            delete.addLore(ChatColor.GRAY + "Create another variant before deleting this one.");
        }
        window.setElement(4, 0, delete);
    }

    private void addMemberships(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            int page
    ) {
        List<JigsawStudioMenuState.Membership> memberships = page(
                variant.memberships(), page, MEMBERSHIPS_PER_PAGE);
        if (memberships.isEmpty()) {
            UIElement empty = element("no-memberships", Material.BARRIER, ChatColor.RED + "No Pool Entries");
            empty.addLore(ChatColor.GRAY + "This variant is not linked from an owned pool.");
            window.setElement(0, 2, empty);
            return;
        }

        for (int index = 0; index < memberships.size(); index++) {
            JigsawStudioMenuState.Membership membership = memberships.get(index);
            int position = GRID_POSITIONS[index % GRID_POSITIONS.length];
            int row = 1 + index / GRID_POSITIONS.length;
            boolean confirmed = variant.active() && variant.owned() && isUnlinkConfirmed(
                    window.getViewer().getUniqueId(),
                    state.requestId(),
                    workcell.stableId(),
                    variant.pieceKey(),
                    membership);
            UIElement element = element(
                    "membership-" + index,
                    confirmed ? Material.REDSTONE : Material.GOLD_NUGGET,
                    (confirmed ? ChatColor.RED : ChatColor.GOLD)
                            + safe(displayKey(membership.poolKey()))
                            + " [" + membership.entryIndex() + "]")
                    .setEnchanted(confirmed);
            element.addLore(ChatColor.DARK_GRAY + safe(membership.poolKey()));
            element.addLore(ChatColor.GRAY + "Exact entry: " + membership.entryIndex());
            element.addLore(ChatColor.WHITE + "Weight: " + membership.weight());
            element.addLore((state.irisExtended() ? ChatColor.WHITE : ChatColor.GRAY)
                    + "Chance: " + chance(membership.chance())
                    + (state.irisExtended() ? "" : " (Iris only)"));
            if (variant.active() && variant.owned()) {
                element.addLore(ChatColor.GREEN + "Left-click: weight +1");
                element.addLore(ChatColor.YELLOW + "Right-click: weight -1");
                if (state.irisExtended()) {
                    element.addLore(ChatColor.GREEN + "Shift-left: chance +"
                            + CHANCE_STEP_PERCENTAGE_POINTS + "%");
                    element.addLore(ChatColor.YELLOW + "Shift-right: chance -"
                            + CHANCE_STEP_PERCENTAGE_POINTS + "%");
                } else {
                    element.addLore(ChatColor.GRAY + "Vanilla-portable pool entries always have 100% chance.");
                }
                element.addLore(confirmed
                        ? ChatColor.RED + "Middle-click again to unlink"
                        : ChatColor.RED + "Middle-click twice to unlink");
                element.onLeftClick(clicked -> adjustMembershipWeight(
                        window.getViewer(), state.requestId(), workcell.stableId(), membership, 1));
                element.onRightClick(clicked -> adjustMembershipWeight(
                        window.getViewer(), state.requestId(), workcell.stableId(), membership, -1));
                if (state.irisExtended()) {
                    element.onShiftLeftClick(clicked -> adjustMembershipChance(
                            window.getViewer(),
                            state.requestId(),
                            workcell.stableId(),
                            membership,
                            CHANCE_STEP_PERCENTAGE_POINTS));
                    element.onShiftRightClick(clicked -> adjustMembershipChance(
                            window.getViewer(),
                            state.requestId(),
                            workcell.stableId(),
                            membership,
                            -CHANCE_STEP_PERCENTAGE_POINTS));
                }
                element.onMiddleClick(clicked -> confirmOrUnlink(
                        window.getViewer(), state.requestId(), workcell.stableId(), membership, page));
            } else {
                element.addLore(variant.active()
                        ? ChatColor.GRAY + "Read-only pool entry"
                        : ChatColor.GRAY + "Load this variant to edit its pool entry");
            }
            window.setElement(position, row, element);
        }
    }

    private void addDetailsFooter(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            int page
    ) {
        UIElement back = element("footer-back", Material.ARROW, ChatColor.YELLOW + "Back");
        back.onLeftClick(clicked -> refreshMain(
                window.getViewer(), state.requestId(), workcell.stableId(), 0));
        window.setElement(-4, 5, back);

        int pageCount = pageCount(variant.memberships().size(), MEMBERSHIPS_PER_PAGE);
        if (page > 0) {
            UIElement previous = element("membership-previous", Material.ARROW, ChatColor.YELLOW + "Previous Page");
            previous.onLeftClick(clicked -> refreshDetails(
                    window.getViewer(),
                    state.requestId(),
                    workcell.stableId(),
                    variant.pieceKey(),
                    page - 1));
            window.setElement(-1, 5, previous);
        }
        UIElement indicator = element("membership-page", Material.BOOK, ChatColor.WHITE + "Page "
                + (page + 1) + "/" + pageCount);
        indicator.addLore(ChatColor.GRAY + "" + variant.memberships().size() + " exact pool entries");
        window.setElement(0, 5, indicator);
        if (page + 1 < pageCount) {
            UIElement next = element("membership-next", Material.ARROW, ChatColor.YELLOW + "Next Page");
            next.onLeftClick(clicked -> refreshDetails(
                    window.getViewer(),
                    state.requestId(),
                    workcell.stableId(),
                    variant.pieceKey(),
                    page + 1));
            window.setElement(1, 5, next);
        }
        window.setElement(2, 5, evaluationElement(state.evaluation()));

        UIElement toolbox = element("details-toolbox", Material.STICK, ChatColor.AQUA + "Toolbox");
        toolbox.onLeftClick(clicked -> openToolbox(
                window.getViewer(), state.requestId(), workcell.stableId(), 0));
        window.setElement(4, 5, toolbox);
    }

    private void renderVariantSettings(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            int requestedPage
    ) {
        int page = clampPage(requestedPage, state.themeSets().size(), THEME_SETS_PER_PAGE);
        window.batch(() -> {
            window.clearElements();

            UIElement back = element("variant-settings-back", Material.ARROW, ChatColor.YELLOW + "Back to Details");
            back.onLeftClick(clicked -> openDetails(
                    window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), 0));
            window.setElement(-4, 0, back);

            UIElement identity = element(
                    "variant-settings-identity",
                    Material.COMPARATOR,
                    ChatColor.GOLD + safe(variant.displayName()) + " Rules");
            identity.addLore(ChatColor.DARK_GRAY + safe(variant.pieceKey()));
            identity.addLore(ChatColor.GRAY + "Theme memberships: " + themes(variant.themes()));
            identity.addLore(ChatColor.GRAY + "Use Duplicate All Enabled Cells as Family for coherent sets.");
            window.setElement(0, 0, identity);
            window.setElement(4, 0, evaluationElement(state.evaluation()));

            if (!variant.active() || !variant.owned() || !state.irisExtended()) {
                UIElement unavailable = element(
                        "variant-settings-unavailable",
                        Material.BARRIER,
                        state.irisExtended()
                                ? ChatColor.RED + "Load an Owned Variant First"
                                : ChatColor.GRAY + "Iris Metadata Unavailable");
                unavailable.addLore(state.irisExtended()
                        ? ChatColor.GRAY + "Rules and themes can only change for the loaded owned variant."
                        : ChatColor.GRAY + "Vanilla-portable pieces use vanilla placement behavior.");
                window.setElement(0, 2, unavailable);
            } else {
                window.setElement(-4, 1, ruleElement(
                        window, state, workcell, variant, RuleField.MINIMUM_DEPTH));
                window.setElement(-2, 1, ruleElement(
                        window, state, workcell, variant, RuleField.MAXIMUM_DEPTH));
                window.setElement(0, 1, ruleElement(
                        window, state, workcell, variant, RuleField.MINIMUM_PLACEMENTS));
                window.setElement(2, 1, ruleElement(
                        window, state, workcell, variant, RuleField.MAXIMUM_PLACEMENTS));

                UIElement terminal = element(
                        "rule-terminal",
                        variant.rules().terminal() ? Material.REDSTONE_TORCH : Material.LEVER,
                        variant.rules().terminal()
                                ? ChatColor.GREEN + "Terminal Piece: Yes"
                                : ChatColor.YELLOW + "Terminal Piece: No");
                terminal.addLore(ChatColor.GRAY + "Terminal pieces consume a connector without expanding.");
                terminal.addLore(ChatColor.YELLOW + "Left-click to toggle");
                terminal.onLeftClick(clicked -> updateVariantRules(
                        window.getViewer(),
                        state.requestId(),
                        workcell.stableId(),
                        variant.pieceKey(),
                        withTerminal(variant.rules(), !variant.rules().terminal())));
                window.setElement(4, 1, terminal);

                addVariantThemeMemberships(window, state, workcell, variant, page);
            }

            UIElement footerBack = element("variant-rules-footer-back", Material.ARROW, ChatColor.YELLOW + "Back");
            footerBack.onLeftClick(clicked -> openDetails(
                    window.getViewer(), state.requestId(), workcell.stableId(), variant.pieceKey(), 0));
            window.setElement(-4, 5, footerBack);

            int pageCount = pageCount(state.themeSets().size(), THEME_SETS_PER_PAGE);
            if (page > 0) {
                UIElement previous = element(
                        "variant-theme-previous",
                        Material.ARROW,
                        ChatColor.YELLOW + "Previous Page");
                previous.onLeftClick(clicked -> refreshVariantSettings(
                        window.getViewer(),
                        state.requestId(),
                        workcell.stableId(),
                        variant.pieceKey(),
                        page - 1));
                window.setElement(-1, 5, previous);
            }
            UIElement indicator = element(
                    "variant-theme-page",
                    Material.BOOK,
                    ChatColor.WHITE + "Theme Page " + (page + 1) + "/" + pageCount);
            indicator.addLore(ChatColor.GRAY + "" + state.themeSets().size() + " declared themes");
            window.setElement(0, 5, indicator);
            if (page + 1 < pageCount) {
                UIElement next = element(
                        "variant-theme-next",
                        Material.ARROW,
                        ChatColor.YELLOW + "Next Page");
                next.onLeftClick(clicked -> refreshVariantSettings(
                        window.getViewer(),
                        state.requestId(),
                        workcell.stableId(),
                        variant.pieceKey(),
                        page + 1));
                window.setElement(1, 5, next);
            }
            window.setElement(4, 5, evaluationElement(state.evaluation()));
        });
    }

    private UIElement ruleElement(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            RuleField field
    ) {
        int value = field.value(variant.rules());
        UIElement element = element(
                "rule-" + field.name().toLowerCase(Locale.ROOT),
                field.material(),
                ChatColor.AQUA + field.displayName() + ": " + field.displayValue(value));
        boolean unlimitedMaximum = field == RuleField.MAXIMUM_PLACEMENTS && value == 0;
        if (unlimitedMaximum) {
            element.addLore(ChatColor.GRAY + "Increase is already unlimited.");
        } else {
            element.addLore(ChatColor.GREEN + "Left-click: +1");
            element.addLore(ChatColor.GREEN + "Shift-left: +" + field.shiftStep());
            element.onLeftClick(clicked -> adjustVariantRule(
                    window.getViewer(), state.requestId(), workcell, variant, field, 1));
            element.onShiftLeftClick(clicked -> adjustVariantRule(
                    window.getViewer(), state.requestId(), workcell, variant, field, field.shiftStep()));
        }
        element.addLore(unlimitedMaximum
                ? ChatColor.YELLOW + "Right-click: set 512"
                : ChatColor.YELLOW + "Right-click: -1");
        element.addLore(unlimitedMaximum
                ? ChatColor.YELLOW + "Shift-right: set 497"
                : ChatColor.YELLOW + "Shift-right: -" + field.shiftStep());
        element.onRightClick(clicked -> adjustVariantRule(
                window.getViewer(), state.requestId(), workcell, variant, field, -1));
        element.onShiftRightClick(clicked -> adjustVariantRule(
                window.getViewer(), state.requestId(), workcell, variant, field, -field.shiftStep()));
        return element;
    }

    private void addVariantThemeMemberships(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            int page
    ) {
        List<JigsawStudioMenuState.ThemeSet> themeSets = page(
                state.themeSets(), page, THEME_SETS_PER_PAGE);
        if (themeSets.isEmpty()) {
            UIElement empty = element(
                    "variant-no-themes",
                    Material.GRAY_DYE,
                    ChatColor.GRAY + "Available to Every Theme");
            empty.addLore(ChatColor.GRAY + "Create a coherent theme set from Structure Rules first.");
            window.setElement(0, 3, empty);
            return;
        }
        for (int index = 0; index < themeSets.size(); index++) {
            JigsawStudioMenuState.ThemeSet themeSet = themeSets.get(index);
            boolean member = variant.themes().contains(themeSet.key());
            int position = GRID_POSITIONS[index % GRID_POSITIONS.length];
            int row = 2 + index / GRID_POSITIONS.length;
            UIElement element = element(
                    "variant-theme-" + index,
                    member ? Material.LIME_DYE : Material.GRAY_DYE,
                    (member ? ChatColor.GREEN : ChatColor.GRAY) + safe(themeSet.key()))
                    .setEnchanted(member);
            element.addLore(member
                    ? ChatColor.GREEN + "This variant belongs to the theme."
                    : ChatColor.GRAY + "This variant does not belong to the theme.");
            element.addLore(ChatColor.YELLOW + "Left-click to toggle membership");
            element.onLeftClick(clicked -> toggleVariantTheme(
                    window.getViewer(), state.requestId(), workcell, variant, themeSet.key()));
            window.setElement(position, row, element);
        }
    }

    private void renderToolbox(
            UIWindow window,
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell,
            int requestedPage
    ) {
        List<ToolboxTool> allTools = toolboxTools(state, workcell);
        int page = clampPage(requestedPage, allTools.size(), TOOLS_PER_PAGE);
        List<ToolboxTool> tools = page(allTools, page, TOOLS_PER_PAGE);
        window.batch(() -> {
            window.clearElements();

            UIElement back = element("toolbox-back", Material.ARROW, ChatColor.YELLOW + "Back to Variants");
            back.onLeftClick(clicked -> refreshMain(
                    window.getViewer(), state.requestId(), workcell.stableId(), 0));
            window.setElement(-4, 0, back);

            UIElement heading = element("toolbox-heading", Material.CHEST, ChatColor.AQUA + "Bound Tool Sticks");
            heading.addLore(ChatColor.GRAY + "Every stick is bound to request " + state.requestId());
            heading.addLore(ChatColor.GRAY + "Stale sticks stop working when this Studio closes.");
            window.setElement(0, 0, heading);
            window.setElement(4, 0, evaluationElement(state.evaluation()));

            for (int index = 0; index < tools.size(); index++) {
                ToolboxTool tool = tools.get(index);
                int position = GRID_POSITIONS[index % GRID_POSITIONS.length];
                int row = 1 + index / GRID_POSITIONS.length;
                UIElement element = element(
                        "tool-" + index,
                        Material.STICK,
                        ChatColor.AQUA + safe(tool.displayName()));
                element.addLore(ChatColor.GRAY + "Action: " + tool.payload().action().displayName());
                addToolBindingLore(element, tool.payload());
                element.addLore(tool.payload().action().destructive()
                        ? ChatColor.RED + "Bound tools require confirmation when used."
                        : ChatColor.YELLOW + "Left-click to receive this named stick.");
                element.onLeftClick(clicked -> giveTool(
                        window.getViewer(), state.requestId(), tool.payload()));
                window.setElement(position, row, element);
            }

            UIElement footerBack = element("toolbox-footer-back", Material.ARROW, ChatColor.YELLOW + "Back");
            footerBack.onLeftClick(clicked -> refreshMain(
                    window.getViewer(), state.requestId(), workcell.stableId(), 0));
            window.setElement(-4, 5, footerBack);

            int pageCount = pageCount(allTools.size(), TOOLS_PER_PAGE);
            if (page > 0) {
                UIElement previous = element("toolbox-previous", Material.ARROW, ChatColor.YELLOW + "Previous Page");
                previous.onLeftClick(clicked -> refreshToolbox(
                        window.getViewer(), state.requestId(), workcell.stableId(), page - 1));
                window.setElement(-1, 5, previous);
            }
            UIElement indicator = element("toolbox-page", Material.BOOK, ChatColor.WHITE + "Page "
                    + (page + 1) + "/" + pageCount);
            indicator.addLore(ChatColor.GRAY + "" + allTools.size() + " bound tools");
            window.setElement(0, 5, indicator);
            if (page + 1 < pageCount) {
                UIElement next = element("toolbox-next", Material.ARROW, ChatColor.YELLOW + "Next Page");
                next.onLeftClick(clicked -> refreshToolbox(
                        window.getViewer(), state.requestId(), workcell.stableId(), page + 1));
                window.setElement(1, 5, next);
            }
        });
    }

    private void selectWorkcell(Player player, UUID requestId, String workcellId) {
        if (matchingState(player, requestId, true).isEmpty()) {
            return;
        }
        if (actions.selectWorkcell(player, workcellId)) {
            clearConfirmations(player.getUniqueId());
            refreshMain(player, requestId, workcellId, 0);
        }
    }

    private void switchVariant(Player player, UUID requestId, String workcellId, String pieceKey) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        if (variant == null) {
            stale(player);
            return;
        }
        if (variant.active()) {
            player.sendMessage(ChatColor.YELLOW + "That variant is already loaded. Right-click it for details.");
            return;
        }
        if (actions.switchVariant(player, workcellId, pieceKey, false)) {
            closeAfterAction(player);
        }
    }

    private void openDetails(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        if (variant == null) {
            stale(player);
            return;
        }
        UIWindow window = windows.get(player.getUniqueId());
        if (window != null) {
            renderDetails(window, current.get(), workcell, variant, page);
        }
    }

    private void openWorkcellSettings(Player player, UUID requestId, String workcellId) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        UIWindow window = windows.get(player.getUniqueId());
        if (workcell == null || window == null) {
            stale(player);
            return;
        }
        renderWorkcellSettings(window, current.get(), workcell);
    }

    private void openStructureSettings(
            Player player,
            UUID requestId,
            String selectedWorkcellId,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        UIWindow window = windows.get(player.getUniqueId());
        if (current.get().workcell(selectedWorkcellId) == null || window == null) {
            stale(player);
            return;
        }
        renderStructureSettings(window, current.get(), selectedWorkcellId, page);
    }

    private void openVariantSettings(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        UIWindow window = windows.get(player.getUniqueId());
        if (workcell == null || variant == null || window == null) {
            stale(player);
            return;
        }
        if (!variant.active() || !variant.owned()) {
            player.sendMessage(ChatColor.YELLOW + "Load an owned variant before editing its rules.");
            return;
        }
        if (!current.get().irisExtended()) {
            player.sendMessage(ChatColor.YELLOW
                    + "Vanilla-portable pieces cannot encode Iris theme or piece-rule metadata.");
            return;
        }
        renderVariantSettings(window, current.get(), workcell, variant, page);
    }

    private void openVariantSizeSettings(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        UIWindow window = windows.get(player.getUniqueId());
        if (workcell == null || variant == null || !variant.owned()
                || variant.dimensions().isEmpty() || window == null) {
            stale(player);
            return;
        }
        renderVariantSizeSettings(window, current.get(), workcell, variant);
    }

    private void openToolbox(Player player, UUID requestId, String workcellId, int page) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        UIWindow window = windows.get(player.getUniqueId());
        if (workcell == null || window == null) {
            stale(player);
            return;
        }
        renderToolbox(window, current.get(), workcell, page);
    }

    private void createVariant(
            Player player,
            UUID requestId,
            String workcellId,
            boolean duplicateActive
    ) {
        if (matchingState(player, requestId, true).isEmpty()) {
            return;
        }
        if (actions.createVariant(player, workcellId, duplicateActive)) {
            closeAfterAction(player);
        }
    }

    private void setWorkcellEnabled(
            Player player,
            UUID requestId,
            String workcellId,
            boolean enabled
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        if (current.get().workcell(workcellId) == null) {
            stale(player);
            return;
        }
        if (actions.setWorkcellEnabled(player, workcellId, enabled)) {
            closeAfterAction(player);
        }
    }

    private void resizeWorkcell(
            Player player,
            UUID requestId,
            String workcellId,
            DimensionAxis axis,
            int delta
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        if (workcell == null) {
            stale(player);
            return;
        }
        Optional<JigsawStudioCellDimensions> adjusted = adjustedDimensions(
                workcell.capacity(), axis, delta);
        if (adjusted.isEmpty()) {
            player.sendMessage(ChatColor.RED + "That workcell size is outside Iris limits.");
            return;
        }
        if (actions.updateWorkcellDimensions(player, workcellId, adjusted.get())) {
            closeAfterAction(player);
        }
    }

    private void resizeVariantAxis(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey,
            DimensionAxis axis,
            int delta
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        if (workcell == null || variant == null || !variant.owned() || variant.dimensions().isEmpty()) {
            stale(player);
            return;
        }
        Optional<JigsawStudioCellDimensions> adjusted = adjustedDimensions(
                variant.dimensions().orElseThrow(), axis, delta);
        if (adjusted.isEmpty()) {
            player.sendMessage(ChatColor.RED + "That variant size is outside Iris limits.");
            return;
        }
        resizeVariant(player, requestId, workcellId, pieceKey, adjusted.get());
    }

    private void resizeVariant(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey,
            JigsawStudioCellDimensions dimensions
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        if (workcell == null || variant == null || !variant.owned()) {
            stale(player);
            return;
        }
        if (dimensions.width() > workcell.capacity().width()
                || dimensions.height() > workcell.capacity().height()
                || dimensions.depth() > workcell.capacity().depth()) {
            player.sendMessage(ChatColor.RED + "Increase this workcell's capacity before making the variant larger.");
            return;
        }
        if (actions.resizeVariant(player, workcellId, pieceKey, dimensions)) {
            closeAfterAction(player);
        }
    }

    private void setRequireCaps(Player player, UUID requestId, boolean requireCaps) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        if (!current.get().irisExtended()) {
            player.sendMessage(ChatColor.YELLOW + "Mandatory caps require Iris compatibility.");
            return;
        }
        if (current.get().requireCaps() == requireCaps) {
            player.sendMessage(ChatColor.YELLOW + "Mandatory caps are already "
                    + (requireCaps ? "enabled." : "disabled."));
            return;
        }
        if (actions.setRequireCaps(player, requireCaps)) {
            clearConfirmations(player.getUniqueId());
            closeAfterAction(player);
        }
    }

    private void duplicateActiveFamily(Player player, UUID requestId, String themeKey) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        if (!current.get().irisExtended()) {
            player.sendMessage(ChatColor.YELLOW + "Theme sets require Iris compatibility.");
            return;
        }
        if (!nextThemeSetKey(current.get().themeSets()).equals(themeKey)) {
            stale(player);
            return;
        }
        if (actions.duplicateActiveFamily(player, themeKey)) {
            clearConfirmations(player.getUniqueId());
            closeAfterAction(player);
        }
    }

    private void adjustThemeSetWeight(
            Player player,
            UUID requestId,
            JigsawStudioMenuState.ThemeSet expected,
            int delta
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        if (!current.get().irisExtended()) {
            player.sendMessage(ChatColor.YELLOW + "Theme weights require Iris compatibility.");
            return;
        }
        JigsawStudioMenuState.ThemeSet themeSet = current.get().themeSet(expected.key());
        if (!expected.equals(themeSet)) {
            stale(player);
            return;
        }
        Optional<Integer> weight = adjustedPositiveValue(themeSet.weight(), delta);
        if (weight.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Theme weights must remain positive.");
            return;
        }
        if (actions.updateThemeSetWeight(player, themeSet.key(), weight.get())) {
            clearConfirmations(player.getUniqueId());
            closeAfterAction(player);
        }
    }

    private void flushNow(Player player, UUID requestId, String workcellId) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        if (workcell == null) {
            stale(player);
            return;
        }
        if (!workcell.dirty()) {
            player.sendMessage(ChatColor.YELLOW + "This workcell has no pending changes to flush.");
            return;
        }
        if (actions.flushAutosave(player, workcellId)) {
            closeAfterAction(player);
        }
    }

    private void goToPreview(Player player, UUID requestId) {
        if (matchingState(player, requestId, true).isPresent()
                && actions.goToPreview(player)) {
            closeAfterAction(player);
        }
    }

    private void toggleRotation(Player player, UUID requestId, String workcellId) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty() || activeVariant(current.get(), workcellId) == null) {
            return;
        }
        if (actions.toggleVariantRotatable(player, workcellId)) {
            closeAfterAction(player);
        }
    }

    private void adjustVariantRule(
            Player player,
            UUID requestId,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant expected,
            RuleField field,
            int delta
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Variant variant = activeVariant(current.get(), workcell.stableId());
        if (variant == null || !variant.owned() || !variant.pieceKey().equals(expected.pieceKey())) {
            stale(player);
            return;
        }
        Optional<JigsawStudioPieceRules> rules = adjustedRules(variant.rules(), field, delta);
        if (rules.isEmpty()) {
            player.sendMessage(ChatColor.RED + "That piece rule value is outside Iris limits.");
            return;
        }
        updateVariantRules(
                player,
                requestId,
                workcell.stableId(),
                variant.pieceKey(),
                rules.get());
    }

    private void updateVariantRules(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey,
            JigsawStudioPieceRules rules
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        if (!current.get().irisExtended()) {
            player.sendMessage(ChatColor.YELLOW + "Piece rules require Iris compatibility.");
            return;
        }
        JigsawStudioMenuState.Variant variant = activeVariant(current.get(), workcellId);
        if (variant == null || !variant.owned() || !variant.pieceKey().equals(pieceKey)) {
            stale(player);
            return;
        }
        if (actions.updateVariantRules(player, workcellId, pieceKey, rules)) {
            clearConfirmations(player.getUniqueId());
            closeAfterAction(player);
        }
    }

    private void toggleVariantTheme(
            Player player,
            UUID requestId,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant expected,
            String themeKey
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        if (!current.get().irisExtended()) {
            player.sendMessage(ChatColor.YELLOW + "Theme membership requires Iris compatibility.");
            return;
        }
        JigsawStudioMenuState.Variant variant = activeVariant(current.get(), workcell.stableId());
        if (variant == null
                || !variant.owned()
                || !variant.pieceKey().equals(expected.pieceKey())
                || current.get().themeSet(themeKey) == null) {
            stale(player);
            return;
        }
        List<String> themes = new ArrayList<>(variant.themes());
        if (!themes.remove(themeKey)) {
            themes.add(themeKey);
        }
        if (actions.updateVariantThemes(
                player,
                workcell.stableId(),
                variant.pieceKey(),
                List.copyOf(themes))) {
            clearConfirmations(player.getUniqueId());
            closeAfterAction(player);
        }
    }

    private void adjustMembershipWeight(
            Player player,
            UUID requestId,
            String workcellId,
            JigsawStudioMenuState.Membership membership,
            int delta
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty() || !hasMembership(current.get(), workcellId, membership)) {
            stale(player);
            return;
        }
        JigsawStudioMenuState.Variant active = activeVariant(current.get(), workcellId);
        if (active == null) {
            stale(player);
            return;
        }
        if (actions.adjustVariantWeight(
                player,
                workcellId,
                active.pieceKey(),
                membership.poolKey(),
                membership.entryIndex(),
                delta)) {
            clearConfirmations(player.getUniqueId());
            closeAfterAction(player);
        }
    }

    private void adjustMembershipChance(
            Player player,
            UUID requestId,
            String workcellId,
            JigsawStudioMenuState.Membership membership,
            int deltaPercentagePoints
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty() || !hasMembership(current.get(), workcellId, membership)) {
            stale(player);
            return;
        }
        if (!current.get().irisExtended()) {
            player.sendMessage(ChatColor.YELLOW + "Per-entry chance requires Iris compatibility.");
            return;
        }
        JigsawStudioMenuState.Variant active = activeVariant(current.get(), workcellId);
        if (active == null) {
            stale(player);
            return;
        }
        if (actions.adjustVariantChance(
                player,
                workcellId,
                active.pieceKey(),
                membership.poolKey(),
                membership.entryIndex(),
                deltaPercentagePoints)) {
            clearConfirmations(player.getUniqueId());
            closeAfterAction(player);
        }
    }

    private void confirmOrUnlink(
            Player player,
            UUID requestId,
            String workcellId,
            JigsawStudioMenuState.Membership membership,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty() || !hasMembership(current.get(), workcellId, membership)) {
            stale(player);
            return;
        }

        UUID playerId = player.getUniqueId();
        JigsawStudioMenuState.Variant active = activeVariant(current.get(), workcellId);
        if (active == null || !active.owned()) {
            stale(player);
            return;
        }
        if (!isUnlinkConfirmed(playerId, requestId, workcellId, active.pieceKey(), membership)) {
            pendingUnlinks.put(playerId, new PendingUnlink(
                    requestId,
                    workcellId,
                    active.pieceKey(),
                    membership.poolKey(),
                    membership.entryIndex(),
                    System.nanoTime() + DESTRUCTIVE_CONFIRM_NANOS));
            J.runEntity(player, () -> refreshDetails(player, requestId, workcellId, page), 1);
            return;
        }

        if (actions.unlinkVariantMembership(
                player,
                workcellId,
                active.pieceKey(),
                membership.poolKey(),
                membership.entryIndex())) {
            clearConfirmations(playerId);
            closeAfterAction(player);
        }
    }

    private void confirmOrDeleteVariant(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant target = variant(workcell, pieceKey);
        if (target == null || !target.owned() || target.active()) {
            stale(player);
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!isDeleteConfirmed(playerId, requestId, workcellId, pieceKey)) {
            pendingDeletes.put(playerId, new PendingDelete(
                    requestId,
                    workcellId,
                    pieceKey,
                    System.nanoTime() + DESTRUCTIVE_CONFIRM_NANOS));
            J.runEntity(player, () -> refreshDetails(
                    player, requestId, workcellId, pieceKey, page), 1);
            return;
        }

        if (actions.deleteVariant(player, workcellId, pieceKey)) {
            clearConfirmations(playerId);
            closeAfterAction(player);
        }
    }

    private void confirmOrDeleteProject(
            Player player,
            UUID requestId,
            String selectedWorkcellId,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!isProjectDeleteConfirmed(playerId, requestId)) {
            pendingProjectDeletes.put(playerId, new PendingProjectDelete(
                    requestId,
                    System.nanoTime() + DESTRUCTIVE_CONFIRM_NANOS));
            J.runEntity(player, () -> refreshStructureSettings(
                    player, requestId, selectedWorkcellId, page), 1);
            return;
        }
        if (actions.deleteProject(player)) {
            clearConfirmations(playerId);
            closeAfterAction(player);
        }
    }

    private void giveTool(Player player, UUID requestId, JigsawStudioToolPayload payload) {
        if (matchingState(player, requestId, true).isEmpty()) {
            return;
        }
        if (!payload.requestId().equals(requestId)) {
            stale(player);
            return;
        }
        actions.giveTool(player, payload);
    }

    private void refreshMain(Player player, UUID requestId, String workcellId, int page) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        UIWindow window = windows.get(player.getUniqueId());
        if (workcell == null || window == null) {
            stale(player);
            return;
        }
        renderMain(window, current.get(), workcell, page);
    }

    private void refreshDetails(Player player, UUID requestId, String workcellId, int page) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Variant active = activeVariant(current.get(), workcellId);
        if (active == null) {
            stale(player);
            return;
        }
        refreshDetails(player, requestId, workcellId, active.pieceKey(), page);
    }

    private void refreshDetails(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        UIWindow window = windows.get(player.getUniqueId());
        if (workcell == null || variant == null || window == null) {
            stale(player);
            return;
        }
        renderDetails(window, current.get(), workcell, variant, page);
    }

    private void refreshStructureSettings(
            Player player,
            UUID requestId,
            String selectedWorkcellId,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        UIWindow window = windows.get(player.getUniqueId());
        if (current.get().workcell(selectedWorkcellId) == null || window == null) {
            stale(player);
            return;
        }
        renderStructureSettings(window, current.get(), selectedWorkcellId, page);
    }

    private void refreshVariantSettings(
            Player player,
            UUID requestId,
            String workcellId,
            String pieceKey,
            int page
    ) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        JigsawStudioMenuState.Variant variant = variant(workcell, pieceKey);
        UIWindow window = windows.get(player.getUniqueId());
        if (workcell == null || variant == null || window == null) {
            stale(player);
            return;
        }
        renderVariantSettings(window, current.get(), workcell, variant, page);
    }

    private void refreshToolbox(Player player, UUID requestId, String workcellId, int page) {
        Optional<JigsawStudioMenuState> current = matchingState(player, requestId, true);
        if (current.isEmpty()) {
            return;
        }
        JigsawStudioMenuState.Workcell workcell = current.get().workcell(workcellId);
        UIWindow window = windows.get(player.getUniqueId());
        if (workcell == null || window == null) {
            stale(player);
            return;
        }
        renderToolbox(window, current.get(), workcell, page);
    }

    private Optional<JigsawStudioMenuState> matchingState(Player player, UUID requestId, boolean closeStale) {
        Optional<JigsawStudioMenuState> current = actions.menuState(player);
        if (current.isPresent() && current.get().requestId().equals(requestId)) {
            return current;
        }
        if (closeStale) {
            stale(player);
        }
        return Optional.empty();
    }

    private void stale(Player player) {
        closeAfterAction(player);
        player.sendMessage(ChatColor.RED + "This Jigsaw Studio menu is stale. Open the control chest again.");
    }

    private void closeAfterAction(Player player) {
        J.runEntity(player, () -> close(player), 1);
    }

    private boolean isUnlinkConfirmed(
            UUID playerId,
            UUID requestId,
            String workcellId,
            String pieceKey,
            JigsawStudioMenuState.Membership membership
    ) {
        PendingUnlink pending = pendingUnlinks.get(playerId);
        if (pending == null) {
            return false;
        }
        if (pending.expiresAtNanos() < System.nanoTime()) {
            pendingUnlinks.remove(playerId, pending);
            return false;
        }
        return pending.requestId().equals(requestId)
                && pending.workcellId().equals(workcellId)
                && pending.pieceKey().equals(pieceKey)
                && pending.poolKey().equals(membership.poolKey())
                && pending.entryIndex() == membership.entryIndex();
    }

    private boolean isDeleteConfirmed(
            UUID playerId,
            UUID requestId,
            String workcellId,
            String pieceKey
    ) {
        PendingDelete pending = pendingDeletes.get(playerId);
        if (pending == null) {
            return false;
        }
        if (pending.expiresAtNanos() < System.nanoTime()) {
            pendingDeletes.remove(playerId, pending);
            return false;
        }
        return pending.requestId().equals(requestId)
                && pending.workcellId().equals(workcellId)
                && pending.pieceKey().equals(pieceKey);
    }

    private boolean isProjectDeleteConfirmed(UUID playerId, UUID requestId) {
        PendingProjectDelete pending = pendingProjectDeletes.get(playerId);
        if (pending == null) {
            return false;
        }
        if (pending.expiresAtNanos() < System.nanoTime()) {
            pendingProjectDeletes.remove(playerId, pending);
            return false;
        }
        return pending.requestId().equals(requestId);
    }

    private void purgeExpiredConfirmations(UUID playerId) {
        PendingUnlink unlink = pendingUnlinks.get(playerId);
        if (unlink != null && unlink.expiresAtNanos() < System.nanoTime()) {
            pendingUnlinks.remove(playerId, unlink);
        }
        PendingDelete delete = pendingDeletes.get(playerId);
        if (delete != null && delete.expiresAtNanos() < System.nanoTime()) {
            pendingDeletes.remove(playerId, delete);
        }
        PendingProjectDelete projectDelete = pendingProjectDeletes.get(playerId);
        if (projectDelete != null && projectDelete.expiresAtNanos() < System.nanoTime()) {
            pendingProjectDeletes.remove(playerId, projectDelete);
        }
    }

    private void clearConfirmations(UUID playerId) {
        pendingUnlinks.remove(playerId);
        pendingDeletes.remove(playerId);
        pendingProjectDeletes.remove(playerId);
    }

    private static boolean hasMembership(
            JigsawStudioMenuState state,
            String workcellId,
            JigsawStudioMenuState.Membership expected
    ) {
        JigsawStudioMenuState.Variant active = activeVariant(state, workcellId);
        if (active == null) {
            return false;
        }
        for (JigsawStudioMenuState.Membership membership : active.memberships()) {
            if (membership.poolKey().equals(expected.poolKey())
                    && membership.entryIndex() == expected.entryIndex()) {
                return true;
            }
        }
        return false;
    }

    private static JigsawStudioMenuState.Variant activeVariant(
            JigsawStudioMenuState state,
            String workcellId
    ) {
        JigsawStudioMenuState.Workcell workcell = state.workcell(workcellId);
        return workcell == null ? null : workcell.activeVariant();
    }

    private static JigsawStudioMenuState.Variant variant(
            JigsawStudioMenuState.Workcell workcell,
            String pieceKey
    ) {
        if (workcell == null || pieceKey == null) {
            return null;
        }
        for (JigsawStudioMenuState.Variant variant : workcell.variants()) {
            if (variant.pieceKey().equals(pieceKey)) {
                return variant;
            }
        }
        return null;
    }

    static String nextThemeSetKey(List<JigsawStudioMenuState.ThemeSet> themeSets) {
        List<JigsawStudioMenuState.ThemeSet> values = List.copyOf(Objects.requireNonNull(
                themeSets,
                "Jigsaw Studio theme sets"));
        int candidate = 1;
        while (candidate > 0) {
            String key = "variant-" + candidate;
            boolean present = false;
            for (JigsawStudioMenuState.ThemeSet themeSet : values) {
                if (Objects.requireNonNull(themeSet, "Jigsaw Studio theme set").key().equals(key)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return key;
            }
            candidate = Math.incrementExact(candidate);
        }
        throw new IllegalStateException("Jigsaw Studio cannot allocate another numbered theme set");
    }

    static Optional<Integer> adjustedPositiveValue(int value, int delta) {
        if (value < 1 || delta == 0) {
            throw new IllegalArgumentException("Jigsaw Studio positive value adjustment is invalid");
        }
        try {
            int adjusted = Math.addExact(value, delta);
            return adjusted < 1 ? Optional.empty() : Optional.of(adjusted);
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    static Optional<JigsawStudioPieceRules> adjustedRules(
            JigsawStudioPieceRules rules,
            RuleField field,
            int delta
    ) {
        JigsawStudioPieceRules current = Objects.requireNonNull(rules, "Jigsaw Studio piece rules");
        RuleField target = Objects.requireNonNull(field, "Jigsaw Studio piece rule field");
        if (delta == 0) {
            throw new IllegalArgumentException("Jigsaw Studio piece rule delta cannot be zero");
        }
        try {
            int minimumDepth = current.minimumDepth();
            int maximumDepth = current.maximumDepth();
            int minimumPlacements = current.minimumPlacements();
            int maximumPlacements = current.maximumPlacements();
            switch (target) {
                case MINIMUM_DEPTH -> minimumDepth = Math.addExact(minimumDepth, delta);
                case MAXIMUM_DEPTH -> maximumDepth = Math.addExact(maximumDepth, delta);
                case MINIMUM_PLACEMENTS -> minimumPlacements = Math.addExact(minimumPlacements, delta);
                case MAXIMUM_PLACEMENTS -> {
                    Optional<Integer> adjustedMaximum = adjustedMaximumPlacements(maximumPlacements, delta);
                    if (adjustedMaximum.isEmpty()) {
                        return Optional.empty();
                    }
                    maximumPlacements = adjustedMaximum.get();
                }
            }
            return Optional.of(new JigsawStudioPieceRules(
                    minimumDepth,
                    maximumDepth,
                    minimumPlacements,
                    maximumPlacements,
                    current.terminal()));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static JigsawStudioPieceRules withTerminal(JigsawStudioPieceRules rules, boolean terminal) {
        JigsawStudioPieceRules current = Objects.requireNonNull(rules, "Jigsaw Studio piece rules");
        return new JigsawStudioPieceRules(
                current.minimumDepth(),
                current.maximumDepth(),
                current.minimumPlacements(),
                current.maximumPlacements(),
                terminal);
    }

    private static Optional<Integer> adjustedMaximumPlacements(int value, int delta) {
        if (value == 0) {
            if (delta > 0) {
                return Optional.empty();
            }
            int adjusted = Math.addExact(513, delta);
            return adjusted < 1 ? Optional.empty() : Optional.of(adjusted);
        }
        int adjusted = Math.addExact(value, delta);
        if (adjusted > 512) {
            return Optional.of(0);
        }
        return adjusted < 1 ? Optional.empty() : Optional.of(adjusted);
    }

    static Optional<JigsawStudioCellDimensions> adjustedDimensions(
            JigsawStudioCellDimensions dimensions,
            DimensionAxis axis,
            int delta
    ) {
        JigsawStudioCellDimensions current = Objects.requireNonNull(
                dimensions,
                "Jigsaw Studio workcell dimensions");
        DimensionAxis target = Objects.requireNonNull(axis, "Jigsaw Studio workcell dimension axis");
        if (delta == 0) {
            throw new IllegalArgumentException("Jigsaw Studio workcell dimension delta cannot be zero");
        }
        try {
            int width = target == DimensionAxis.WIDTH
                    ? Math.addExact(current.width(), delta)
                    : current.width();
            int height = target == DimensionAxis.HEIGHT
                    ? Math.addExact(current.height(), delta)
                    : current.height();
            int depth = target == DimensionAxis.DEPTH
                    ? Math.addExact(current.depth(), delta)
                    : current.depth();
            return Optional.of(new JigsawStudioCellDimensions(width, height, depth));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static List<ToolboxTool> toolboxTools(
            JigsawStudioMenuState state,
            JigsawStudioMenuState.Workcell workcell
    ) {
        JigsawStudioMenuState menu = Objects.requireNonNull(state, "Jigsaw Studio toolbox state");
        JigsawStudioMenuState.Workcell selected = Objects.requireNonNull(
                workcell,
                "Jigsaw Studio toolbox workcell");
        UUID requestId = menu.requestId();
        List<ToolboxTool> tools = new ArrayList<>();
        tools.add(new ToolboxTool(
                "Open Control Menu",
                JigsawStudioToolPayload.request(JigsawStudioToolAction.OPEN_MENU, requestId)));
        tools.add(new ToolboxTool(
                "Select " + selected.displayName(),
                JigsawStudioToolPayload.workcell(
                        JigsawStudioToolAction.SELECT_WORKCELL,
                        requestId,
                        selected.stableId())));
        if (menu.mode() == JigsawStudioMode.PLANAR_JIGSAW) {
            tools.add(new ToolboxTool(
                    "Toggle " + selected.displayName(),
                    JigsawStudioToolPayload.workcell(
                            JigsawStudioToolAction.TOGGLE_WORKCELL,
                            requestId,
                            selected.stableId())));
        }
        tools.add(new ToolboxTool(
                "Resize " + selected.displayName() + " Capacity",
                JigsawStudioToolPayload.workcell(
                        JigsawStudioToolAction.RESIZE_WORKCELL,
                        requestId,
                        selected.stableId())));
        tools.add(new ToolboxTool(
                "Rename " + selected.displayName(),
                JigsawStudioToolPayload.workcell(
                        JigsawStudioToolAction.RENAME_WORKCELL,
                        requestId,
                        selected.stableId())));
        tools.add(new ToolboxTool(
                "New Blank " + selected.displayName() + " Variant",
                JigsawStudioToolPayload.workcell(
                        JigsawStudioToolAction.CREATE_VARIANT,
                        requestId,
                        selected.stableId())));
        tools.add(new ToolboxTool(
                "Go to Preview",
                JigsawStudioToolPayload.request(JigsawStudioToolAction.PREVIEW_GRAPH, requestId)));
        tools.add(new ToolboxTool(
                "Flush " + selected.displayName() + " Autosave",
                JigsawStudioToolPayload.workcell(
                        JigsawStudioToolAction.FLUSH_AUTOSAVE,
                        requestId,
                        selected.stableId())));
        if (menu.irisExtended()) {
            tools.add(new ToolboxTool(
                    "Duplicate All Enabled Cells as Family: " + nextThemeSetKey(menu.themeSets()),
                    JigsawStudioToolPayload.request(JigsawStudioToolAction.DUPLICATE_FAMILY, requestId)));
        }

        JigsawStudioMenuState.Variant active = selected.activeVariant();
        if (active != null) {
            if (active.owned()) {
                tools.add(new ToolboxTool(
                        "Duplicate This Cell's Variant: " + active.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.DUPLICATE_VARIANT,
                                requestId,
                                selected.stableId(),
                                active.pieceKey())));
            }
            if (active.owned()) {
                tools.add(new ToolboxTool(
                        "Rename This Variant: " + active.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.RENAME_VARIANT,
                                requestId,
                                selected.stableId(),
                                active.pieceKey())));
                tools.add(new ToolboxTool(
                        "Resize This Variant: " + active.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.RESIZE_VARIANT,
                                requestId,
                                selected.stableId(),
                                active.pieceKey())));
            }
            if (active.rotationEditable()) {
                tools.add(new ToolboxTool(
                        "Toggle Rotation: " + active.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.TOGGLE_ROTATION,
                                requestId,
                                selected.stableId(),
                                active.pieceKey())));
            }
            if (active.owned() && active.resizableToCapacity()) {
                tools.add(new ToolboxTool(
                        "Resize " + active.displayName() + " to Capacity",
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.EXPAND_TO_CELL,
                                requestId,
                                selected.stableId(),
                                active.pieceKey())));
            }
            if (active.owned() && menu.irisExtended()) {
                tools.add(new ToolboxTool(
                        "Open Theme Memberships: " + active.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.SET_THEME,
                                requestId,
                                selected.stableId(),
                                active.pieceKey())));
                tools.add(new ToolboxTool(
                        "Open Piece Rules: " + active.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.SET_PIECE_RULES,
                                requestId,
                                selected.stableId(),
                                active.pieceKey())));
            }
            if (active.owned()) {
                for (JigsawStudioMenuState.Membership membership : active.memberships()) {
                    addMembershipTools(
                            tools,
                            requestId,
                            selected,
                            active,
                            membership,
                            menu.irisExtended());
                }
            }
        }
        for (JigsawStudioMenuState.Variant variant : selected.variants()) {
            if (variant.active()) {
                continue;
            }
            tools.add(new ToolboxTool(
                    "Load " + variant.displayName(),
                    JigsawStudioToolPayload.variant(
                            JigsawStudioToolAction.LOAD_VARIANT,
                            requestId,
                            selected.stableId(),
                            variant.pieceKey())));
            if (variant.owned()) {
                tools.add(new ToolboxTool(
                        "Rename " + variant.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.RENAME_VARIANT,
                                requestId,
                                selected.stableId(),
                                variant.pieceKey())));
                tools.add(new ToolboxTool(
                        "Resize " + variant.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.RESIZE_VARIANT,
                                requestId,
                                selected.stableId(),
                                variant.pieceKey())));
            }
            if (variant.owned() && selected.variants().size() > 1) {
                tools.add(new ToolboxTool(
                        "Delete " + variant.displayName(),
                        JigsawStudioToolPayload.variant(
                                JigsawStudioToolAction.DELETE_VARIANT,
                                requestId,
                                selected.stableId(),
                                variant.pieceKey())));
            }
        }
        if (menu.irisExtended()) {
            tools.add(new ToolboxTool(
                    menu.requireCaps() ? "Disable Mandatory Caps" : "Enable Mandatory Caps",
                    JigsawStudioToolPayload.request(JigsawStudioToolAction.TOGGLE_REQUIRE_CAPS, requestId)));
        }
        tools.add(new ToolboxTool(
                "Delete Project",
                JigsawStudioToolPayload.request(JigsawStudioToolAction.DELETE_PROJECT, requestId)));
        return List.copyOf(tools);
    }

    private static void addMembershipTools(
            List<ToolboxTool> tools,
            UUID requestId,
            JigsawStudioMenuState.Workcell workcell,
            JigsawStudioMenuState.Variant variant,
            JigsawStudioMenuState.Membership membership,
            boolean chanceEditable
    ) {
        String membershipName = displayKey(membership.poolKey()) + " [" + membership.entryIndex() + "]";
        tools.add(new ToolboxTool(
                "Weight +1: " + membershipName,
                JigsawStudioToolPayload.membership(
                        JigsawStudioToolAction.ADJUST_VARIANT_WEIGHT,
                        requestId,
                        workcell.stableId(),
                        variant.pieceKey(),
                        membership.poolKey(),
                        membership.entryIndex(),
                        1)));
        tools.add(new ToolboxTool(
                "Weight -1: " + membershipName,
                JigsawStudioToolPayload.membership(
                        JigsawStudioToolAction.ADJUST_VARIANT_WEIGHT,
                        requestId,
                        workcell.stableId(),
                        variant.pieceKey(),
                        membership.poolKey(),
                        membership.entryIndex(),
                        -1)));
        if (chanceEditable) {
            tools.add(new ToolboxTool(
                    "Chance +" + CHANCE_STEP_PERCENTAGE_POINTS + "%: " + membershipName,
                    JigsawStudioToolPayload.membership(
                            JigsawStudioToolAction.ADJUST_VARIANT_CHANCE,
                            requestId,
                            workcell.stableId(),
                            variant.pieceKey(),
                            membership.poolKey(),
                            membership.entryIndex(),
                            CHANCE_STEP_PERCENTAGE_POINTS)));
            tools.add(new ToolboxTool(
                    "Chance -" + CHANCE_STEP_PERCENTAGE_POINTS + "%: " + membershipName,
                    JigsawStudioToolPayload.membership(
                            JigsawStudioToolAction.ADJUST_VARIANT_CHANCE,
                            requestId,
                            workcell.stableId(),
                            variant.pieceKey(),
                            membership.poolKey(),
                            membership.entryIndex(),
                            -CHANCE_STEP_PERCENTAGE_POINTS)));
        }
        tools.add(new ToolboxTool(
                "Unlink " + membershipName,
                JigsawStudioToolPayload.membership(
                        JigsawStudioToolAction.UNLINK_MEMBERSHIP,
                        requestId,
                        workcell.stableId(),
                        variant.pieceKey(),
                        membership.poolKey(),
                        membership.entryIndex(),
                        0)));
    }

    private static void addToolBindingLore(UIElement element, JigsawStudioToolPayload payload) {
        if (!payload.workcellId().isEmpty()) {
            element.addLore(ChatColor.GRAY + "Workcell: " + safe(payload.workcellId()));
        }
        if (!payload.pieceKey().isEmpty()) {
            element.addLore(ChatColor.GRAY + "Variant: " + safe(payload.pieceKey()));
        }
        if (!payload.poolKey().isEmpty()) {
            element.addLore(ChatColor.GRAY + "Pool: " + safe(payload.poolKey())
                    + (payload.entryIndex() < 0 ? "" : " [" + payload.entryIndex() + "]"));
        }
        if (payload.amount() != 0) {
            element.addLore(ChatColor.GRAY + "Amount: " + payload.amount());
        }
    }

    static int pageCount(int itemCount, int pageSize) {
        if (itemCount < 0 || pageSize < 1) {
            throw new IllegalArgumentException("Jigsaw Studio menu page bounds are invalid");
        }
        return Math.max(1, (itemCount + pageSize - 1) / pageSize);
    }

    static int clampPage(int requestedPage, int itemCount, int pageSize) {
        int maximum = pageCount(itemCount, pageSize) - 1;
        return Math.max(0, Math.min(requestedPage, maximum));
    }

    static <T> List<T> page(List<T> items, int requestedPage, int pageSize) {
        List<T> values = List.copyOf(Objects.requireNonNull(items, "Jigsaw Studio menu page items"));
        int page = clampPage(requestedPage, values.size(), pageSize);
        int start = page * pageSize;
        int end = Math.min(values.size(), start + pageSize);
        return values.subList(start, end);
    }

    private static UIElement evaluationElement(JigsawStudioMenuState.Evaluation evaluation) {
        UIElement element = element(
                "evaluation",
                evaluationMaterial(evaluation.state()),
                evaluationColor(evaluation.state()) + evaluationName(evaluation.state()));
        element.addLore(ChatColor.GRAY + "Evaluation updates automatically.");
        element.addLore(ChatColor.GRAY + "Seed: " + evaluation.seed());
        if (evaluation.generation() > 0L) {
            element.addLore(ChatColor.GRAY + "Generation: " + evaluation.generation());
        }
        if (!evaluation.selectedTheme().isEmpty()) {
            element.addLore(ChatColor.GRAY + "Theme: " + safe(evaluation.selectedTheme()));
        }
        element.addLore(ChatColor.GRAY + "Pieces: " + evaluation.pieceCount());
        if (!evaluation.detail().isEmpty()) {
            element.addLore(ChatColor.GRAY + safe(evaluation.detail()));
        }
        return element;
    }

    static Material evaluationMaterial(JigsawStudioEvaluationState state) {
        return switch (state) {
            case PENDING -> Material.CLOCK;
            case VALID -> Material.EMERALD;
            case WARNING -> Material.YELLOW_DYE;
            case INVALID -> Material.RED_DYE;
            case STALE -> Material.GRAY_DYE;
        };
    }

    private static ChatColor evaluationColor(JigsawStudioEvaluationState state) {
        return switch (state) {
            case PENDING -> ChatColor.AQUA;
            case VALID -> ChatColor.GREEN;
            case WARNING -> ChatColor.YELLOW;
            case INVALID -> ChatColor.RED;
            case STALE -> ChatColor.GRAY;
        };
    }

    private static String evaluationName(JigsawStudioEvaluationState state) {
        return switch (state) {
            case PENDING -> "Evaluation Pending";
            case VALID -> "Ready to Assemble";
            case WARNING -> "Ready with Warnings";
            case INVALID -> "Assembly Blocked";
            case STALE -> "Evaluation Stale";
        };
    }

    private static Material workcellMaterial(JigsawStudioMenuState.Workcell workcell, boolean selected) {
        if (!workcell.enabled()) {
            return Material.GRAY_WOOL;
        }
        if (workcell.loading() || workcell.saving()) {
            return Material.YELLOW_WOOL;
        }
        if (workcell.dirty()) {
            return Material.ORANGE_WOOL;
        }
        return selected ? Material.LIME_WOOL : Material.LIGHT_GRAY_WOOL;
    }

    static Material variantMaterial(JigsawStudioMenuState.Variant variant) {
        if (variant.active()) {
            return Material.JIGSAW;
        }
        return variant.owned() ? Material.PAPER : Material.GRAY_DYE;
    }

    static String workcellStatus(JigsawStudioMenuState.Workcell workcell) {
        if (workcell.loading()) {
            return ChatColor.YELLOW + "Loading variant";
        }
        if (workcell.saving()) {
            return ChatColor.YELLOW + "Autosave in progress";
        }
        if (workcell.dirty()) {
            return ChatColor.GOLD + "Autosave pending";
        }
        return ChatColor.GREEN + "Autosaved";
    }

    private static String dimensions(JigsawStudioCellDimensions dimensions) {
        return dimensions.width() + "x" + dimensions.height() + "x" + dimensions.depth();
    }

    private static String themes(List<String> themes) {
        return themes.isEmpty() ? "None" : safe(String.join(", ", themes));
    }

    private static String maximumPlacements(int maximum) {
        return maximum == 0 ? "Unlimited" : Integer.toString(maximum);
    }

    private static String chance(double chance) {
        return String.format(Locale.ROOT, "%.1f%%", chance * 100D);
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private static String compatibilityName(JigsawStudioCompatibilityTarget target) {
        return switch (target) {
            case IRIS_EXTENDED -> "Iris Extended";
            case VANILLA_PORTABLE -> "Vanilla Portable";
        };
    }

    private static UIElement element(String id, Material material, String name) {
        return new UIElement(id)
                .setMaterial(new MaterialBlock(material))
                .setName(name);
    }

    private static String title(String structureKey) {
        String value = "Iris Jigsaw: " + safe(displayKey(structureKey));
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private static String displayKey(String resourceKey) {
        int separator = resourceKey.lastIndexOf('/');
        return separator < 0 ? resourceKey : resourceKey.substring(separator + 1);
    }

    private static String safe(String value) {
        return BoardSVC.untrustedBoardValue(value);
    }

    public interface Actions {
        Optional<JigsawStudioMenuState> menuState(Player player);

        boolean selectWorkcell(Player player, String workcellId);

        boolean switchVariant(Player player, String workcellId, String pieceKey, boolean discardDirty);

        boolean createVariant(Player player, String workcellId, boolean duplicateActive);

        boolean setWorkcellEnabled(Player player, String workcellId, boolean enabled);

        boolean updateWorkcellDimensions(
                Player player,
                String workcellId,
                JigsawStudioCellDimensions dimensions
        );

        boolean updateWorkcellDisplayName(Player player, String workcellId, String displayName);

        boolean setRequireCaps(Player player, boolean requireCaps);

        boolean duplicateActiveFamily(Player player, String themeKey);

        boolean updateThemeSetWeight(Player player, String themeKey, int weight);

        boolean flushAutosave(Player player, String workcellId);

        boolean goToPreview(Player player);

        boolean toggleVariantRotatable(Player player, String workcellId);

        boolean expandVariantToCell(Player player, String workcellId);

        boolean resizeVariant(
                Player player,
                String workcellId,
                String pieceKey,
                JigsawStudioCellDimensions dimensions
        );

        boolean updateVariantDisplayName(
                Player player,
                String workcellId,
                String pieceKey,
                String displayName
        );

        boolean updateVariantThemes(
                Player player,
                String workcellId,
                String pieceKey,
                List<String> themes
        );

        boolean updateVariantRules(
                Player player,
                String workcellId,
                String pieceKey,
                JigsawStudioPieceRules rules
        );

        boolean adjustVariantWeight(
                Player player,
                String workcellId,
                String pieceKey,
                String poolKey,
                int entryIndex,
                int delta
        );

        boolean adjustVariantChance(
                Player player,
                String workcellId,
                String pieceKey,
                String poolKey,
                int entryIndex,
                int deltaPercentagePoints
        );

        boolean unlinkVariantMembership(
                Player player,
                String workcellId,
                String pieceKey,
                String poolKey,
                int entryIndex
        );

        boolean deleteVariant(Player player, String workcellId, String pieceKey);

        boolean deleteProject(Player player);

        boolean giveTool(Player player, JigsawStudioToolPayload payload);
    }

    enum RuleField {
        MINIMUM_DEPTH("Minimum Depth", Material.LIGHT_BLUE_DYE, RULE_SHIFT_STEP) {
            @Override
            int value(JigsawStudioPieceRules rules) {
                return rules.minimumDepth();
            }
        },
        MAXIMUM_DEPTH("Maximum Depth", Material.BLUE_DYE, RULE_SHIFT_STEP) {
            @Override
            int value(JigsawStudioPieceRules rules) {
                return rules.maximumDepth();
            }
        },
        MINIMUM_PLACEMENTS("Minimum Placements", Material.TARGET, PLACEMENT_RULE_SHIFT_STEP) {
            @Override
            int value(JigsawStudioPieceRules rules) {
                return rules.minimumPlacements();
            }
        },
        MAXIMUM_PLACEMENTS("Maximum Placements", Material.GREEN_DYE, PLACEMENT_RULE_SHIFT_STEP) {
            @Override
            int value(JigsawStudioPieceRules rules) {
                return rules.maximumPlacements();
            }

            @Override
            String displayValue(int value) {
                return maximumPlacements(value);
            }
        };

        private final String displayName;
        private final Material material;
        private final int shiftStep;

        RuleField(String displayName, Material material, int shiftStep) {
            this.displayName = displayName;
            this.material = material;
            this.shiftStep = shiftStep;
        }

        String displayName() {
            return displayName;
        }

        Material material() {
            return material;
        }

        int shiftStep() {
            return shiftStep;
        }

        String displayValue(int value) {
            return Integer.toString(value);
        }

        abstract int value(JigsawStudioPieceRules rules);
    }

    enum DimensionAxis {
        WIDTH("Width") {
            @Override
            int value(JigsawStudioCellDimensions dimensions) {
                return dimensions.width();
            }
        },
        HEIGHT("Height") {
            @Override
            int value(JigsawStudioCellDimensions dimensions) {
                return dimensions.height();
            }
        },
        DEPTH("Depth") {
            @Override
            int value(JigsawStudioCellDimensions dimensions) {
                return dimensions.depth();
            }
        };

        private final String displayName;

        DimensionAxis(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }

        abstract int value(JigsawStudioCellDimensions dimensions);
    }

    record ToolboxTool(String displayName, JigsawStudioToolPayload payload) {
        ToolboxTool {
            displayName = Objects.requireNonNull(displayName, "Jigsaw Studio toolbox display name").trim();
            if (displayName.isEmpty()) {
                throw new IllegalArgumentException("Jigsaw Studio toolbox display name cannot be blank");
            }
            payload = Objects.requireNonNull(payload, "Jigsaw Studio toolbox payload");
        }
    }

    private record PendingUnlink(
            UUID requestId,
            String workcellId,
            String pieceKey,
            String poolKey,
            int entryIndex,
            long expiresAtNanos
    ) {
    }

    private record PendingDelete(
            UUID requestId,
            String workcellId,
            String pieceKey,
            long expiresAtNanos
    ) {
    }

    private record PendingProjectDelete(UUID requestId, long expiresAtNanos) {
    }
}
