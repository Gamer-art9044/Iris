package art.arcane.iris.core.service.tree;

@FunctionalInterface
public interface BlockDropRouter {
    boolean routeDrop(Object drop);
}
