package art.arcane.iris.api.terrain;

@FunctionalInterface
public interface IrisColumnSink {
    void accept(int blockX, int blockZ, int surfaceHeight, IrisSurfaceKind kind, String biomeKey);
}
