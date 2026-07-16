/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.structure.studio;

import art.arcane.iris.core.structure.authoring.StructureBackend;
import art.arcane.iris.core.structure.authoring.StructureCapability;
import art.arcane.iris.core.structure.authoring.StructureKey;
import art.arcane.iris.core.structure.authoring.StructureOwnershipManifest;
import art.arcane.iris.core.structure.authoring.StructureResourceBundle;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.ObjectPlaceMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SimpleStructureStudioCompilerTest {
    @Test
    public void publishIdentityMustMatchTheRuntimeResourceKey() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleStructureStudioPublishConfig(
                new StructureKey("iris", "studio/owned"),
                "studio/different",
                7,
                8,
                ObjectPlaceMode.STRUCTURE_PIECE
        ));
    }

    @Test
    public void compilesDeterministicOwnedGraphWithExactConnectorGeometry() throws IOException {
        CompilerFixture fixture = fixture();

        StructureResourceBundle first = SimpleStructureStudioCompiler.compile(
                fixture.draft(),
                fixture.config(),
                fixture.objects()
        );
        StructureResourceBundle second = SimpleStructureStudioCompiler.compile(
                fixture.draft(),
                fixture.config(),
                fixture.objects()
        );

        assertEquals(StructureBackend.IRIS_ASSEMBLY, first.backend());
        assertEquals(new StructureKey("iris", "studio/hall"), first.key());
        assertTrue(first.capabilities().contains(StructureCapability.CONNECTORS));
        assertTrue(first.capabilities().contains(StructureCapability.IRIS_PLACEMENT));
        assertEquals(12, first.resources().size());
        assertEquals(resourceHashes(first), resourceHashes(second));

        StructureOwnershipManifest manifest = StructureOwnershipManifest.from(first);
        assertEquals(first.key(), manifest.structure());
        assertEquals(resourceHashes(first), manifest.resourceHashes());

        JsonObject startPiece = json(first, "jigsaw-pieces/studio/hall/cells/0-0/start.json");
        assertFalse(startPiece.get("rotatable").getAsBoolean());
        JsonArray startConnectors = startPiece.getAsJsonArray("connectors");
        assertEquals(1, startConnectors.size());
        assertConnector(startConnectors.get(0).getAsJsonObject(), 3, 2, 0, "NORTH_NEGATIVE_Z");

        JsonObject mainPiece = json(first, "jigsaw-pieces/studio/hall/cells/1-0/cross.json");
        assertTrue(mainPiece.get("rotatable").getAsBoolean());
        JsonArray mainConnectors = mainPiece.getAsJsonArray("connectors");
        assertEquals(4, mainConnectors.size());
        assertConnector(mainConnectors.get(0).getAsJsonObject(), 3, 2, 0, "NORTH_NEGATIVE_Z");
        assertConnector(mainConnectors.get(1).getAsJsonObject(), 5, 2, 4, "EAST_POSITIVE_X");
        assertConnector(mainConnectors.get(2).getAsJsonObject(), 3, 2, 7, "SOUTH_POSITIVE_Z");
        assertConnector(mainConnectors.get(3).getAsJsonObject(), 0, 2, 4, "WEST_NEGATIVE_X");

        JsonObject mainPool = json(first, "jigsaw-pools/studio/hall/main.json");
        assertEquals("studio/hall/terminal", mainPool.get("fallback").getAsString());
        assertEquals(3, mainPool.getAsJsonArray("pieces").get(0).getAsJsonObject().get("weight").getAsInt());
        assertEquals(5, mainPool.getAsJsonArray("pieces").get(1).getAsJsonObject().get("weight").getAsInt());
        JsonObject startPool = json(first, "jigsaw-pools/studio/hall/start.json");
        assertEquals(1, startPool.getAsJsonArray("pieces").size());
        JsonObject terminalPool = json(first, "jigsaw-pools/studio/hall/terminal.json");
        assertEquals(1, terminalPool.getAsJsonArray("pieces").size());

        JsonObject structure = json(first, "structures/studio/hall.json");
        assertEquals("studio/hall/start", structure.get("startPool").getAsString());
        assertEquals(1, structure.get("maxDepth").getAsInt());
        assertEquals(4, structure.get("maxSizeChunks").getAsInt());
        assertEquals("STRUCTURE_PIECE", structure.get("placeMode").getAsString());
    }

    @Test
    public void failsClosedForHalfTurnOnlyPieces() {
        CompilerFixture fixture = fixture();
        SimpleStructureStudioCell halfTurnMain = fixture.draft().cellOrEmpty(1, 0)
                .withRotationPolicy(SimpleStructureStudioRotationPolicy.HALF_TURNS);
        SimpleStructureStudioDraft unsupported = fixture.draft().withCell(halfTurnMain);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> SimpleStructureStudioCompiler.compile(unsupported, fixture.config(), fixture.objects())
        );

        assertTrue(failure.getMessage().contains("HALF_TURNS"));
    }

    @Test
    public void rejectsMissingUnexpectedAndWrongSizedResolvedObjects() {
        CompilerFixture fixture = fixture();
        LinkedHashMap<SimpleStructureStudioVariantKey, IrisObject> missing = new LinkedHashMap<>(fixture.objects());
        missing.remove(new SimpleStructureStudioVariantKey(1, 0, "cross"));

        IllegalStateException missingFailure = assertThrows(
                IllegalStateException.class,
                () -> SimpleStructureStudioCompiler.compile(fixture.draft(), fixture.config(), missing)
        );
        assertTrue(missingFailure.getMessage().contains("missing=[1,0:cross]"));

        LinkedHashMap<SimpleStructureStudioVariantKey, IrisObject> unexpected = new LinkedHashMap<>(fixture.objects());
        unexpected.put(new SimpleStructureStudioVariantKey(3, 0, "stale"), object());
        IllegalStateException unexpectedFailure = assertThrows(
                IllegalStateException.class,
                () -> SimpleStructureStudioCompiler.compile(fixture.draft(), fixture.config(), unexpected)
        );
        assertTrue(unexpectedFailure.getMessage().contains("unexpected=[3,0:stale]"));

        LinkedHashMap<SimpleStructureStudioVariantKey, IrisObject> wrongSize = new LinkedHashMap<>(fixture.objects());
        wrongSize.put(new SimpleStructureStudioVariantKey(2, 0, "cap"), new IrisObject(5, 4, 8));
        IllegalStateException sizeFailure = assertThrows(
                IllegalStateException.class,
                () -> SimpleStructureStudioCompiler.compile(fixture.draft(), fixture.config(), wrongSize)
        );
        assertTrue(sizeFailure.getMessage().contains("expected 6x4x8"));
    }

    @Test
    public void rejectsDisconnectedPoolChannelsBeforeWritingResources() {
        CompilerFixture fixture = fixture();
        SimpleStructureStudioCell wrongChannel = fixture.draft().cellOrEmpty(2, 0)
                .withConnector("iris:other", 2);
        SimpleStructureStudioDraft disconnected = fixture.draft().withCell(wrongChannel);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> SimpleStructureStudioCompiler.compile(disconnected, fixture.config(), fixture.objects())
        );

        assertTrue(failure.getMessage().contains("must cover the same connector channels"));
    }

    private CompilerFixture fixture() {
        SimpleStructureStudioLayout layout = new SimpleStructureStudioLayout(3, 1, 6, 8, 4);
        SimpleStructureStudioCell start = SimpleStructureStudioCell
                .create(0, 0, SimpleStructureStudioTopology.START)
                .withRotationPolicy(SimpleStructureStudioRotationPolicy.FIXED)
                .withConnector("iris:path", 2)
                .addVariant(new SimpleStructureStudioVariant("start", 1));
        SimpleStructureStudioCell main = SimpleStructureStudioCell
                .create(1, 0, SimpleStructureStudioTopology.CROSS)
                .withQuarterTurns(1)
                .withConnector("iris:path", 2)
                .addVariant(new SimpleStructureStudioVariant("cross", 3))
                .addVariant(new SimpleStructureStudioVariant("mossy/cross", 5));
        SimpleStructureStudioCell terminal = SimpleStructureStudioCell
                .create(2, 0, SimpleStructureStudioTopology.TERMINAL)
                .withConnector("iris:path", 2)
                .addVariant(new SimpleStructureStudioVariant("cap", 2));
        SimpleStructureStudioDraft draft = new SimpleStructureStudioDraft(
                layout,
                773L,
                List.of(terminal, main, start)
        );
        SimpleStructureStudioPublishConfig config = new SimpleStructureStudioPublishConfig(
                new StructureKey("iris", "studio/hall"),
                "studio/hall",
                1,
                4,
                ObjectPlaceMode.STRUCTURE_PIECE
        );
        LinkedHashMap<SimpleStructureStudioVariantKey, IrisObject> objects = new LinkedHashMap<>();
        objects.put(SimpleStructureStudioVariantKey.of(start, start.variants().get(0)), object());
        objects.put(SimpleStructureStudioVariantKey.of(main, main.variants().get(0)), object());
        objects.put(SimpleStructureStudioVariantKey.of(main, main.variants().get(1)), object());
        objects.put(SimpleStructureStudioVariantKey.of(terminal, terminal.variants().get(0)), object());
        return new CompilerFixture(draft, config, objects);
    }

    private IrisObject object() {
        return new IrisObject(6, 4, 8);
    }

    private Map<String, String> resourceHashes(StructureResourceBundle bundle) {
        TreeMap<String, String> hashes = new TreeMap<>();
        for (StructureResourceBundle.Resource resource : bundle.resources().values()) {
            hashes.put(resource.relativePath(), resource.contentHash());
        }
        return hashes;
    }

    private JsonObject json(StructureResourceBundle bundle, String relativePath) {
        StructureResourceBundle.Resource resource = bundle.resources().get(relativePath);
        return JsonParser.parseString(new String(resource.content(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void assertConnector(
            JsonObject connector,
            int x,
            int y,
            int z,
            String direction
    ) {
        JsonObject position = connector.getAsJsonObject("position");
        assertEquals(x, position.get("x").getAsInt());
        assertEquals(y, position.get("y").getAsInt());
        assertEquals(z, position.get("z").getAsInt());
        assertEquals(direction, connector.get("direction").getAsString());
        assertEquals("studio/hall/main", connector.get("pool").getAsString());
        assertEquals("iris:path", connector.get("name").getAsString());
        assertEquals("iris:path", connector.get("targetName").getAsString());
        assertEquals("ALIGNED", connector.get("joint").getAsString());
    }

    private record CompilerFixture(
            SimpleStructureStudioDraft draft,
            SimpleStructureStudioPublishConfig config,
            Map<SimpleStructureStudioVariantKey, IrisObject> objects
    ) {
    }
}
