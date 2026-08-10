package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("The orientation-independent connector shape represented by a planar jigsaw workcell.")
public enum IrisJigsawWorkcellArchetype {
    BLANK(0),
    END(1),
    STRAIGHT(5),
    CORNER(3),
    TEE(11),
    CROSS(15);

    private final int canonicalMask;

    IrisJigsawWorkcellArchetype(int canonicalMask) {
        this.canonicalMask = canonicalMask;
    }

    public static IrisJigsawWorkcellArchetype fromPiece(IrisJigsawPiece piece) {
        if (piece == null || piece.getConnectors() == null) {
            return BLANK;
        }
        return fromMask(horizontalMask(piece));
    }

    public static IrisJigsawWorkcellArchetype fromMask(int mask) {
        if (mask < 0 || mask > 15) {
            throw new IllegalArgumentException("Planar jigsaw topology mask must be between 0 and 15");
        }
        int connections = Integer.bitCount(mask);
        return switch (connections) {
            case 0 -> BLANK;
            case 1 -> END;
            case 2 -> mask == 5 || mask == 10 ? STRAIGHT : CORNER;
            case 3 -> TEE;
            case 4 -> CROSS;
            default -> throw new IllegalStateException("Unreachable planar jigsaw topology");
        };
    }

    public static int horizontalMask(IrisJigsawPiece piece) {
        if (piece == null || piece.getConnectors() == null) {
            return 0;
        }
        int mask = 0;
        for (IrisJigsawConnector connector : piece.getConnectors()) {
            if (connector == null || connector.getDirection() == null) {
                continue;
            }
            mask |= switch (connector.getDirection()) {
                case NORTH_NEGATIVE_Z -> 1;
                case EAST_POSITIVE_X -> 2;
                case SOUTH_POSITIVE_Z -> 4;
                case WEST_NEGATIVE_X -> 8;
                case UP_POSITIVE_Y, DOWN_NEGATIVE_Y -> 0;
            };
        }
        return mask;
    }

    public int sourceToCanonicalQuarterTurns(IrisJigsawPiece piece) {
        int sourceMask = horizontalMask(piece);
        if (fromMask(sourceMask) != this) {
            throw new IllegalArgumentException("Jigsaw piece does not match workcell archetype " + name());
        }
        for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
            int rotated = quarterTurns == 0
                    ? sourceMask
                    : ((sourceMask << quarterTurns) | (sourceMask >>> (4 - quarterTurns))) & 15;
            if (rotated == canonicalMask) {
                return quarterTurns;
            }
        }
        throw new IllegalStateException("No canonical rotation exists for planar jigsaw piece");
    }
}
