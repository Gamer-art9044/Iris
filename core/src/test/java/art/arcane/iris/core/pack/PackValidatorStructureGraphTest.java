package art.arcane.iris.core.pack;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PackValidatorStructureGraphTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsCompleteStructureGraph() throws Exception {
        File pack = temporaryFolder.newFolder("pack");
        write(pack, "dimensions/main.json", "{\"structures\":[{\"structures\":[\"castle\"]}]}");
        write(pack, "structures/castle.json", "{\"startPool\":\"castle/start\"}");
        write(pack, "jigsaw-pools/castle/start.json", "{\"pieces\":[{\"piece\":\"castle/start\"}],\"fallback\":\"castle/end\"}");
        write(pack, "jigsaw-pools/castle/end.json", "{\"pieces\":[]}");
        write(pack, "jigsaw-pieces/castle/start.json", "{\"object\":\"castle/start\",\"connectors\":[{\"pool\":\"castle/end\"}]}");
        write(pack, "objects/castle/start.iob", "object");

        assertTrue(PackValidator.validateStructureGraph(pack).isEmpty());
    }

    @Test
    public void reportsMissingReferencesInDeterministicGraphOrder() throws Exception {
        File pack = temporaryFolder.newFolder("pack");
        write(pack, "dimensions/main.json", "{\"structures\":[{\"structures\":[\"missing-structure\"]}]}");
        write(pack, "structures/castle.json", "{\"startPool\":\"missing-start\"}");
        write(pack, "jigsaw-pools/castle/start.json", "{\"pieces\":[{\"piece\":\"missing-piece\"}],\"fallback\":\"missing-fallback\"}");
        write(pack, "jigsaw-pieces/castle/start.json", "{\"object\":\"castle/start\",\"connectors\":[{\"pool\":\"missing-connector-pool\"}]}");
        write(pack, "objects/castle/start.iob", "object");

        List<String> errors = PackValidator.validateStructureGraph(pack);

        assertEquals(List.of(
                "Dimension 'main' structures[0].structures[0] references missing structure 'missing-structure'.",
                "Structure 'castle' references missing start pool 'missing-start'.",
                "Jigsaw pool 'castle/start' pieces[0] references missing piece 'missing-piece'.",
                "Jigsaw pool 'castle/start' references missing fallback pool 'missing-fallback'.",
                "Jigsaw piece 'castle/start' connectors[0] references missing pool 'missing-connector-pool'."
        ), errors);
    }

    @Test
    public void ignoresLegacyGeneratedStructureIndex() throws Exception {
        File pack = temporaryFolder.newFolder("pack");
        write(pack, "structures/structure-index.json", "{\"counts\":{},\"structureSets\":{},\"iris\":[]}");

        assertTrue(PackValidator.validateStructureGraph(pack).isEmpty());
    }

    @Test
    public void reportsMalformedStructureJson() throws Exception {
        File pack = temporaryFolder.newFolder("pack");
        write(pack, "structures/castle.json", "{");

        List<String> errors = PackValidator.validateStructureGraph(pack);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Structure 'castle' has invalid JSON:"));
    }

    @Test
    public void reportsMalformedJigsawPoolJson() throws Exception {
        File pack = temporaryFolder.newFolder("pack");
        write(pack, "jigsaw-pools/castle/start.json", "{");

        List<String> errors = PackValidator.validateStructureGraph(pack);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Jigsaw pool 'castle/start' has invalid JSON:"));
    }

    @Test
    public void reportsMalformedJigsawPieceJson() throws Exception {
        File pack = temporaryFolder.newFolder("pack");
        write(pack, "jigsaw-pieces/castle/start.json", "{");

        List<String> errors = PackValidator.validateStructureGraph(pack);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Jigsaw piece 'castle/start' has invalid JSON:"));
    }

    @Test
    public void reportsMissingJigsawPieceObject() throws Exception {
        File pack = temporaryFolder.newFolder("pack");
        write(pack, "jigsaw-pieces/castle/start.json", "{\"object\":\"castle/missing\"}");

        assertEquals(List.of(
                "Jigsaw piece 'castle/start' references missing object 'castle/missing'."
        ), PackValidator.validateStructureGraph(pack));
    }

    private void write(File root, String relative, String content) throws Exception {
        Path path = root.toPath().resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
