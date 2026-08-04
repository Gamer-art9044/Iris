package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A registered structure with an EMPTY biome filter is legal datapack content: opt-in structures
 * (e.g. Towns and Towers' towns_and_towers:exclusives/*) ship biome tags that only reference
 * optional modded biomes, so the filter resolves to zero entries on a vanilla server. Resolving
 * biome keys for such a structure must return an empty set (the structure is simply unreachable),
 * never throw — a throw here aborts the whole /iris structure verify run and degrades /iris find.
 * The defensive throw is only kept for a NON-empty filter whose holders all fail to resolve keys.
 */
public class VanillaStructureBiomesEmptyFilterContractTest {
    @Test
    public void emptyBiomeFilterReturnsEmptyKeysInsteadOfThrowing() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.vanillaStructureBiomesSource")));

        assertTrue(source.contains("if (keys.isEmpty() && hasFilterEntries)"));
        assertTrue(source.contains("has biome filter entries but none resolve to registered biome keys"));
        assertFalse(source.contains("exposes no registered biome keys"));
    }

    @Test
    public void moddedHookMatchesEmptyFilterContract() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.moddedStructureHooksSource")));

        assertTrue(source.contains("if (keys.isEmpty() && hasFilterEntries)"));
        assertTrue(source.contains("has biome filter entries but none resolve to registered biome keys"));
        assertFalse(source.contains("exposes no registered biome keys"));
    }
}
