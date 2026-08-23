package art.arcane.iris.util.project.matter.slices;

import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveHydrology;
import art.arcane.volmlib.util.data.palette.Palette;
import art.arcane.volmlib.util.matter.Sliced;
import art.arcane.volmlib.util.matter.slices.RawMatter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@Sliced
public final class RiverCaveHydrologyMatter extends RawMatter<RiverCaveHydrology> {
    public RiverCaveHydrologyMatter() {
        this(1, 1, 1);
    }

    public RiverCaveHydrologyMatter(int width, int height, int depth) {
        super(width, height, depth, RiverCaveHydrology.class);
    }

    @Override
    public Palette<RiverCaveHydrology> getGlobalPalette() {
        return null;
    }

    @Override
    public void writeNode(RiverCaveHydrology hydrology, DataOutputStream output) throws IOException {
        output.writeByte(actionCode(hydrology.action()));
        output.writeUTF(hydrology.floodedBiomeKey());
    }

    @Override
    public RiverCaveHydrology readNode(DataInputStream input) throws IOException {
        RiverCaveAction action = actionFromCode(input.readUnsignedByte());
        return new RiverCaveHydrology(action, input.readUTF());
    }

    private int actionCode(RiverCaveAction action) {
        return switch (action) {
            case WET_SOURCE -> 1;
            case FALLING_WATER -> 2;
            case DRY_AIR -> 3;
            case SEAL_GUARD -> 4;
        };
    }

    private RiverCaveAction actionFromCode(int code) throws IOException {
        return switch (code) {
            case 1 -> RiverCaveAction.WET_SOURCE;
            case 2 -> RiverCaveAction.FALLING_WATER;
            case 3 -> RiverCaveAction.DRY_AIR;
            case 4 -> RiverCaveAction.SEAL_GUARD;
            default -> throw new IOException("Unknown river cave hydrology action code " + code);
        };
    }
}
