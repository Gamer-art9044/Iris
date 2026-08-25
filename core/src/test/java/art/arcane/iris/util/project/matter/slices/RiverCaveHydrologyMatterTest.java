package art.arcane.iris.util.project.matter.slices;

import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveFluidKind;
import art.arcane.iris.engine.river.cave.RiverCaveHydrology;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class RiverCaveHydrologyMatterTest {
    @Test
    public void everyActionAndBiomeRoundTrips() throws IOException {
        RiverCaveHydrologyMatter matter = new RiverCaveHydrologyMatter();
        for (RiverCaveAction action : RiverCaveAction.values()) {
            for (RiverCaveFluidKind fluidKind : RiverCaveFluidKind.values()) {
                RiverCaveHydrology expected = new RiverCaveHydrology(
                        action,
                        "iris:flooded_grotto",
                        fluidKind
                );
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                matter.writeNode(expected, new DataOutputStream(bytes));

                RiverCaveHydrology actual = matter.readNode(
                        new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

                assertEquals(expected, actual);
            }
        }
    }

    @Test
    public void emptyBiomeAndInvalidActionRemainUnambiguous() throws IOException {
        RiverCaveHydrologyMatter matter = new RiverCaveHydrologyMatter();
        RiverCaveHydrology expected = RiverCaveHydrology.of(RiverCaveAction.DRY_AIR);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        matter.writeNode(expected, new DataOutputStream(bytes));

        assertEquals(expected, matter.readNode(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertThrows(IOException.class, () -> matter.readNode(
                new DataInputStream(new ByteArrayInputStream(new byte[]{99}))));
        assertThrows(IOException.class, () -> matter.readNode(
                new DataInputStream(new ByteArrayInputStream(new byte[]{1, 99}))));
    }
}
