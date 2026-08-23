package art.arcane.iris.core.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class DesktopUiMessages {
    public static final TextKey VISION_TITLE = TextKey.of("iris.desktop.vision.title", "Iris Vision");
    public static final TextKey VISION_VIEW = TextKey.of("iris.desktop.vision.view", "View:");
    public static final TextKey VISION_GRID = TextKey.of("iris.desktop.vision.grid", "Grid");
    public static final TextKey VISION_FOLLOW = TextKey.of("iris.desktop.vision.follow", "Follow");
    public static final TextKey VISION_REFRESHING = TextKey.of("iris.desktop.vision.refreshing", "Refreshing");
    public static final TextKey VISION_FPS = TextKey.of("iris.desktop.vision.fps", "{fps} FPS");
    public static final TextKey VISION_ZOOM_RESET = TextKey.of("iris.desktop.vision.zoom_reset", "Zoom reset");
    public static final TextKey VISION_GRID_ENABLED = TextKey.of("iris.desktop.vision.grid_enabled", "Grid enabled");
    public static final TextKey VISION_GRID_DISABLED = TextKey.of("iris.desktop.vision.grid_disabled", "Grid disabled");
    public static final TextKey VISION_FOLLOWING = TextKey.of("iris.desktop.vision.following", "Following {player}");
    public static final TextKey VISION_NO_PLAYER = TextKey.of("iris.desktop.vision.no_player", "No player in world");
    public static final TextKey VISION_FOLLOW_DISABLED = TextKey.of("iris.desktop.vision.follow_disabled", "Follow disabled");
    public static final TextKey VISION_STATUS_LEFT = TextKey.of("iris.desktop.vision.status_left", "{mode}  |  {bpp} bpp  |  {width} x {height} blocks");
    public static final TextKey VISION_STATUS_RIGHT = TextKey.of("iris.desktop.vision.status_right", "X: {x}  Z: {z}  |  {fps} FPS");
    public static final TextKey VISION_ENTITY_POSITION = TextKey.of("iris.desktop.vision.entity_position", "Position: {x}, {y}, {z}");
    public static final TextKey VISION_ENTITY_HEALTH = TextKey.of("iris.desktop.vision.entity_health", "Health: {health} / {maximum}");
    public static final TextKey VISION_BLOCK_POSITION = TextKey.of("iris.desktop.vision.block_position", "Block {x}, {z}");
    public static final TextKey VISION_CHUNK_POSITION = TextKey.of("iris.desktop.vision.chunk_position", "Chunk {x}, {z}");
    public static final TextKey VISION_REGION_POSITION = TextKey.of("iris.desktop.vision.region_position", "Region {x}, {z}");
    public static final TextKey VISION_BIOME_KEY = TextKey.of("iris.desktop.vision.biome_key", "Key: {key}");
    public static final TextKey VISION_BIOME_FILE = TextKey.of("iris.desktop.vision.biome_file", "File: {file}");
    public static final TextKey VISION_VELOCITY = TextKey.of("iris.desktop.vision.velocity", "Velocity: {velocity}");
    public static final TextKey VISION_TILES = TextKey.of("iris.desktop.vision.tiles", "Atlas pages: {ready} / {total} exact");
    public static final TextKey VISION_WORKERS = TextKey.of("iris.desktop.vision.workers", "Workers: {active} active / {queued} queued");
    public static final TextKey VISION_CENTER = TextKey.of("iris.desktop.vision.center", "Center: {x}, {z}");
    public static final TextKey VISION_HELP_TOGGLE = TextKey.of("iris.desktop.vision.help.toggle", "Toggle help");
    public static final TextKey VISION_HELP_REFRESH = TextKey.of("iris.desktop.vision.help.refresh", "Refresh tiles");
    public static final TextKey VISION_HELP_FOLLOW = TextKey.of("iris.desktop.vision.help.follow", "Follow player");
    public static final TextKey VISION_HELP_ZOOM = TextKey.of("iris.desktop.vision.help.zoom", "Zoom in/out");
    public static final TextKey VISION_HELP_RESET_ZOOM = TextKey.of("iris.desktop.vision.help.reset_zoom", "Reset zoom");
    public static final TextKey VISION_HELP_CYCLE_MODE = TextKey.of("iris.desktop.vision.help.cycle_mode", "Cycle render mode");
    public static final TextKey VISION_HELP_FPS = TextKey.of("iris.desktop.vision.help.fps", "Toggle 30/60 FPS");
    public static final TextKey VISION_HELP_GRID = TextKey.of("iris.desktop.vision.help.grid", "Toggle grid");
    public static final TextKey VISION_HELP_BIOME = TextKey.of("iris.desktop.vision.help.biome", "Detailed biome info");
    public static final TextKey VISION_HELP_TELEPORT = TextKey.of("iris.desktop.vision.help.teleport", "Teleport to cursor");
    public static final TextKey VISION_HELP_EDITOR = TextKey.of("iris.desktop.vision.help.editor", "Open biome in editor");
    public static final TextKey VISION_OPENED = TextKey.of("iris.desktop.vision.opened", "Opened {target}");
    public static final TextKey VISION_TELEPORTING = TextKey.of("iris.desktop.vision.teleporting", "Teleporting to {x}, {z}");
    public static final TextKey VISION_MODE_BIOME = TextKey.of("iris.desktop.vision.mode.biome", "Biome");
    public static final TextKey VISION_MODE_BIOME_LAND = TextKey.of("iris.desktop.vision.mode.biome_land", "Biome land");
    public static final TextKey VISION_MODE_BIOME_SEA = TextKey.of("iris.desktop.vision.mode.biome_sea", "Biome sea");
    public static final TextKey VISION_MODE_REGION = TextKey.of("iris.desktop.vision.mode.region", "Region");
    public static final TextKey VISION_MODE_CAVE_LAND = TextKey.of("iris.desktop.vision.mode.cave_land", "Cave land");
    public static final TextKey VISION_MODE_RIVER = TextKey.of("iris.desktop.vision.mode.river", "River network");
    public static final TextKey VISION_MODE_HEIGHT = TextKey.of("iris.desktop.vision.mode.height", "Height");
    public static final TextKey VISION_MODE_OBJECT_LOAD = TextKey.of("iris.desktop.vision.mode.object_load", "Object load");
    public static final TextKey VISION_MODE_DECORATOR_LOAD = TextKey.of("iris.desktop.vision.mode.decorator_load", "Decorator load");
    public static final TextKey VISION_MODE_CONTINENT = TextKey.of("iris.desktop.vision.mode.continent", "Continent");
    public static final TextKey VISION_MODE_LAYER_LOAD = TextKey.of("iris.desktop.vision.mode.layer_load", "Layer load");
    public static final TextKey NOISE_TITLE = TextKey.of("iris.desktop.noise.title", "Noise Explorer");
    public static final TextKey NOISE_TITLE_GENERATOR = TextKey.of("iris.desktop.noise.title_generator", "Noise Explorer: {generator}");
    public static final TextKey NOISE_SEARCH = TextKey.of("iris.desktop.noise.search", "Search...");
    public static final TextKey NOISE_STATUS = TextKey.of("iris.desktop.noise.status", "{name}  |  X: {x}  Z: {z}  |  Zoom: {zoom}  |  Value: {value}  |  {fps} FPS");
    public static final TextKey NOISE_CATEGORY_CUSTOM = TextKey.of("iris.desktop.noise.category.custom", "Custom");
    public static final TextKey NOISE_CATEGORY_PACK_GENERATORS = TextKey.of("iris.desktop.noise.category.pack_generators", "Pack Generators");
    public static final TextKey NOISE_CATEGORY_SIMPLEX = TextKey.of("iris.desktop.noise.category.simplex", "Simplex");
    public static final TextKey NOISE_CATEGORY_PERLIN = TextKey.of("iris.desktop.noise.category.perlin", "Perlin");
    public static final TextKey NOISE_CATEGORY_CELLULAR = TextKey.of("iris.desktop.noise.category.cellular", "Cellular");
    public static final TextKey NOISE_CATEGORY_IRIS = TextKey.of("iris.desktop.noise.category.iris", "Iris");
    public static final TextKey NOISE_CATEGORY_CLOVER = TextKey.of("iris.desktop.noise.category.clover", "Clover");
    public static final TextKey NOISE_CATEGORY_HEXAGON = TextKey.of("iris.desktop.noise.category.hexagon", "Hexagon");
    public static final TextKey NOISE_CATEGORY_VASCULAR = TextKey.of("iris.desktop.noise.category.vascular", "Vascular");
    public static final TextKey NOISE_CATEGORY_GLOBE = TextKey.of("iris.desktop.noise.category.globe", "Globe");
    public static final TextKey NOISE_CATEGORY_CUBIC = TextKey.of("iris.desktop.noise.category.cubic", "Cubic");
    public static final TextKey NOISE_CATEGORY_FRACTAL = TextKey.of("iris.desktop.noise.category.fractal", "Fractal");
    public static final TextKey NOISE_CATEGORY_STATIC = TextKey.of("iris.desktop.noise.category.static", "Static");
    public static final TextKey NOISE_CATEGORY_NOWHERE = TextKey.of("iris.desktop.noise.category.nowhere", "Nowhere");
    public static final TextKey NOISE_CATEGORY_SIERPINSKI = TextKey.of("iris.desktop.noise.category.sierpinski", "Sierpinski");
    public static final TextKey NOISE_CATEGORY_UTILITY = TextKey.of("iris.desktop.noise.category.utility", "Utility");
    public static final TextKey NOISE_CATEGORY_OTHER = TextKey.of("iris.desktop.noise.category.other", "Other");
    public static final TextKey PREGEN_INITIALIZING = TextKey.of("iris.desktop.pregen.initializing", "Initializing...");
    public static final TextKey PREGEN_TITLE = TextKey.of("iris.desktop.pregen.title", "Pregen View");
    public static final TextKey PREGEN_METHOD_PENDING = TextKey.of("iris.desktop.pregen.method_pending", "Pending");
    public static final TextKey PREGEN_PAUSED = TextKey.of("iris.desktop.pregen.paused", "PAUSED");
    public static final TextKey PREGEN_RESUME_HINT = TextKey.of("iris.desktop.pregen.resume_hint", "Press P to resume");
    public static final TextKey PREGEN_PAUSE_HINT = TextKey.of("iris.desktop.pregen.pause_hint", "Press P to pause");
    public static final TextKey PREGEN_PROGRESS_PAUSED = TextKey.of("iris.desktop.pregen.progress_paused", "PAUSED {generated} of {total} ({percent} complete)");
    public static final TextKey PREGEN_PROGRESS_SAVING = TextKey.of("iris.desktop.pregen.progress_saving", "Saving... {generated} of {total} ({percent} complete)");
    public static final TextKey PREGEN_PROGRESS_GENERATING = TextKey.of("iris.desktop.pregen.progress_generating", "Generating {generated} of {total} ({percent} complete)");
    public static final TextKey PREGEN_SPEED = TextKey.of("iris.desktop.pregen.speed", "Speed: overall {overall}, 10s {tenSecond}, 30s {thirtySecond}, 60s {sixtySecond} chunks/s");
    public static final TextKey PREGEN_SPEED_CACHED = TextKey.of("iris.desktop.pregen.speed_cached", "Speed (cached): overall {overall}, 10s {tenSecond}, 30s {thirtySecond}, 60s {sixtySecond} chunks/s");
    public static final TextKey PREGEN_TIME = TextKey.of("iris.desktop.pregen.time", "{remaining} remaining ({elapsed} elapsed)");
    public static final TextKey PREGEN_METHOD = TextKey.of("iris.desktop.pregen.method", "Generation method: {method}");
    public static final TextKey PREGEN_MEMORY = TextKey.of("iris.desktop.pregen.memory", "Memory: {used} ({usage}) Pressure: {pressure}/s");

    private static final List<MessageKey> KEYS = List.of(
            VISION_TITLE, VISION_VIEW, VISION_GRID, VISION_FOLLOW,
            VISION_REFRESHING, VISION_FPS, VISION_ZOOM_RESET, VISION_GRID_ENABLED, VISION_GRID_DISABLED,
            VISION_FOLLOWING, VISION_NO_PLAYER, VISION_FOLLOW_DISABLED,
            VISION_STATUS_LEFT, VISION_STATUS_RIGHT, VISION_ENTITY_POSITION, VISION_ENTITY_HEALTH,
            VISION_BLOCK_POSITION, VISION_CHUNK_POSITION, VISION_REGION_POSITION, VISION_BIOME_KEY,
            VISION_BIOME_FILE, VISION_VELOCITY, VISION_TILES, VISION_WORKERS, VISION_CENTER,
            VISION_HELP_TOGGLE, VISION_HELP_REFRESH, VISION_HELP_FOLLOW, VISION_HELP_ZOOM,
            VISION_HELP_RESET_ZOOM, VISION_HELP_CYCLE_MODE, VISION_HELP_FPS,
            VISION_HELP_GRID, VISION_HELP_BIOME, VISION_HELP_TELEPORT, VISION_HELP_EDITOR, VISION_OPENED,
            VISION_TELEPORTING, VISION_MODE_BIOME, VISION_MODE_BIOME_LAND, VISION_MODE_BIOME_SEA,
            VISION_MODE_REGION, VISION_MODE_CAVE_LAND, VISION_MODE_RIVER, VISION_MODE_HEIGHT, VISION_MODE_OBJECT_LOAD,
            VISION_MODE_DECORATOR_LOAD, VISION_MODE_CONTINENT, VISION_MODE_LAYER_LOAD, NOISE_TITLE,
            NOISE_TITLE_GENERATOR, NOISE_SEARCH, NOISE_STATUS, NOISE_CATEGORY_CUSTOM,
            NOISE_CATEGORY_PACK_GENERATORS, NOISE_CATEGORY_SIMPLEX, NOISE_CATEGORY_PERLIN,
            NOISE_CATEGORY_CELLULAR, NOISE_CATEGORY_IRIS, NOISE_CATEGORY_CLOVER, NOISE_CATEGORY_HEXAGON,
            NOISE_CATEGORY_VASCULAR, NOISE_CATEGORY_GLOBE, NOISE_CATEGORY_CUBIC, NOISE_CATEGORY_FRACTAL,
            NOISE_CATEGORY_STATIC, NOISE_CATEGORY_NOWHERE, NOISE_CATEGORY_SIERPINSKI,
            NOISE_CATEGORY_UTILITY, NOISE_CATEGORY_OTHER, PREGEN_INITIALIZING, PREGEN_TITLE,
            PREGEN_METHOD_PENDING, PREGEN_PAUSED, PREGEN_RESUME_HINT, PREGEN_PAUSE_HINT,
            PREGEN_PROGRESS_PAUSED, PREGEN_PROGRESS_SAVING, PREGEN_PROGRESS_GENERATING, PREGEN_SPEED,
            PREGEN_SPEED_CACHED, PREGEN_TIME, PREGEN_METHOD, PREGEN_MEMORY
    );

    private DesktopUiMessages() {
    }

    public static List<MessageKey> keys() {
        return KEYS;
    }
}
