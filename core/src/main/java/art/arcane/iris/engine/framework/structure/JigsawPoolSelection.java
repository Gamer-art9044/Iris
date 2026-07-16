package art.arcane.iris.engine.framework.structure;

import art.arcane.iris.engine.object.IrisJigsawPool;

import java.util.List;
import java.util.Objects;

public final class JigsawPoolSelection {
    private JigsawPoolSelection() {
    }

    public static String directFallbackKey(IrisJigsawPool pool) {
        Objects.requireNonNull(pool);
        return normalize(pool.getFallback());
    }

    public static List<String> candidatePoolKeys(
            String primaryKey,
            IrisJigsawPool primaryPool,
            boolean includePrimary
    ) {
        Objects.requireNonNull(primaryPool);
        String normalizedPrimary = normalize(primaryKey);
        String directFallback = directFallbackKey(primaryPool);
        if (!includePrimary) {
            return directFallback.isEmpty() ? List.of() : List.of(directFallback);
        }
        if (normalizedPrimary.isEmpty()) {
            return List.of();
        }
        return directFallback.isEmpty()
                ? List.of(normalizedPrimary)
                : List.of(normalizedPrimary, directFallback);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
