package art.arcane.iris.engine.framework.structure;

import java.util.List;

public record StructureGraphAssemblySample(long seed, Outcome outcome) {
    public record Outcome(List<String> pieceKeys, int unresolvedConnectorCount, boolean pieceCapReached,
                          boolean intentionalEmpty) {
        public Outcome {
            pieceKeys = List.copyOf(pieceKeys);
        }

        public Outcome(List<String> pieceKeys, int unresolvedConnectorCount, boolean pieceCapReached) {
            this(pieceKeys, unresolvedConnectorCount, pieceCapReached, false);
        }

        public boolean isViable() {
            return (intentionalEmpty || !pieceKeys.isEmpty()) && !pieceCapReached;
        }

        public boolean isComplete() {
            return isViable() && unresolvedConnectorCount == 0;
        }
    }
}
