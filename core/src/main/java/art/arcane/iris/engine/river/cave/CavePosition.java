package art.arcane.iris.engine.river.cave;

public record CavePosition(int x, int y, int z) {
    public CavePosition offset(int dx, int dy, int dz) {
        return new CavePosition(x + dx, y + dy, z + dz);
    }
}
