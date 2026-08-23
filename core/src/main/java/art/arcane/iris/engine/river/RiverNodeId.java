package art.arcane.iris.engine.river;

public record RiverNodeId(long cellX, long cellZ) implements Comparable<RiverNodeId> {
    public long stableId() {
        return RiverNetwork.mix(cellX * 0x9E3779B97F4A7C15L ^ Long.rotateLeft(cellZ * 0xC2B2AE3D27D4EB4FL, 31));
    }

    @Override
    public int compareTo(RiverNodeId other) {
        int xComparison = Long.compare(cellX, other.cellX);
        return xComparison != 0 ? xComparison : Long.compare(cellZ, other.cellZ);
    }
}
