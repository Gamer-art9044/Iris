package art.arcane.iris;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoadOrder;
import org.junit.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PaperPluginMetadataTest {
    private static final List<String> OPTIONAL_PLUGIN_IDS = List.of(
            "PlaceholderAPI",
            "CraftEngine",
            "Nexo",
            "ItemsAdder",
            "SCore",
            "ExecutableItems",
            "MythicLib",
            "MMOItems",
            "eco",
            "EcoItems",
            "MythicMobs",
            "MythicCrucible",
            "KGenerators",
            "Multiverse-Core",
            "WorldEdit"
    );
    private static final List<String> JOINED_PLUGIN_IDS = OPTIONAL_PLUGIN_IDS.stream()
            .filter(pluginId -> !"ExecutableItems".equals(pluginId) && !"Multiverse-Core".equals(pluginId))
            .toList();
    private static final List<String> BUKKIT_SOFT_DEPEND_IDS = OPTIONAL_PLUGIN_IDS.stream()
            .filter(pluginId -> !"Multiverse-Core".equals(pluginId))
            .toList();

    @Test
    public void paperMetadataDeclaresBootstrapAndFoliaSupport() throws Exception {
        String metadata;
        try (InputStream stream = PaperPluginMetadataTest.class.getResourceAsStream("/paper-plugin.yml")) {
            assertNotNull(stream);
            metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(metadata.contains("bootstrapper: " + IrisBootstrap.class.getName()));
        assertTrue(metadata.contains("folia-supported: true"));
        assertTrue(metadata.contains("load: STARTUP"));
        assertFalse(metadata.contains("commands:"));
        for (String pluginId : JOINED_PLUGIN_IDS) {
            assertTrue(metadata.contains(optionalDependencyBlock(pluginId, "BEFORE", true)));
        }
        assertTrue(metadata.contains(optionalDependencyBlock("ExecutableItems", "BEFORE", false)));
        assertTrue(metadata.contains(optionalDependencyBlock("Multiverse-Core", "AFTER", true)));
    }

    @Test
    public void bukkitMetadataRetainsStartupCommandAndAliases() throws Exception {
        PluginDescriptionFile metadata;
        try (InputStream stream = PaperPluginMetadataTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            metadata = new PluginDescriptionFile(stream);
        }

        assertEquals(Iris.class.getName(), metadata.getMain());
        assertEquals(PluginLoadOrder.STARTUP, metadata.getLoad());
        Map<String, Map<String, Object>> commands = metadata.getCommands();
        assertTrue(commands.containsKey("iris"));
        assertEquals(List.of("ir", "irs"), commands.get("iris").get("aliases"));
        assertEquals(BUKKIT_SOFT_DEPEND_IDS, metadata.getSoftDepend());
        assertEquals(List.of("Multiverse-Core"), metadata.getLoadBeforePlugins());
    }

    @Test
    public void processedResourcesContainBothMetadataFormats() throws Exception {
        URL paperMetadata = PaperPluginMetadataTest.class.getResource("/paper-plugin.yml");
        assertNotNull(paperMetadata);
        assertEquals("file", paperMetadata.getProtocol());
        Path bukkitMetadata = Path.of(paperMetadata.toURI()).resolveSibling("plugin.yml");
        assertTrue(Files.isRegularFile(bukkitMetadata));
        assertFalse(Files.readString(bukkitMetadata, StandardCharsets.UTF_8).contains("${"));
    }

    private static String optionalDependencyBlock(String pluginId, String loadOrder, boolean joinClasspath) {
        return "    " + pluginId + ":\n"
                + "      load: " + loadOrder + "\n"
                + "      required: false\n"
                + "      join-classpath: " + joinClasspath + "\n";
    }
}
