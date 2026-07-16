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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class SimpleStructureStudioSession {
    public static final int DEFAULT_HISTORY_LIMIT = 64;
    public static final int MAX_HISTORY_LIMIT = 256;

    private static final long PREVIEW_SEED_STEP = 0x9E3779B97F4A7C15L;

    private final int historyLimit;
    private final Deque<SimpleStructureStudioDraft> undoHistory;
    private final Deque<SimpleStructureStudioDraft> redoHistory;
    private SimpleStructureStudioDraft draft;
    private SimpleStructureStudioDraft savedDraft;

    private SimpleStructureStudioSession(SimpleStructureStudioDraft draft, int historyLimit) {
        this.draft = Objects.requireNonNull(draft, "draft");
        if (historyLimit <= 0 || historyLimit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException(
                    "History limit must be between 1 and " + MAX_HISTORY_LIMIT + ": " + historyLimit
            );
        }
        this.historyLimit = historyLimit;
        undoHistory = new ArrayDeque<>(historyLimit);
        redoHistory = new ArrayDeque<>(historyLimit);
        savedDraft = draft;
    }

    public static SimpleStructureStudioSession open(SimpleStructureStudioDraft draft, int historyLimit) {
        return new SimpleStructureStudioSession(draft, historyLimit);
    }

    public static SimpleStructureStudioSession createNew(SimpleStructureStudioDraft draft, int historyLimit) {
        SimpleStructureStudioSession session = new SimpleStructureStudioSession(draft, historyLimit);
        session.savedDraft = null;
        return session;
    }

    public synchronized SimpleStructureStudioDraft draft() {
        return draft;
    }

    public int historyLimit() {
        return historyLimit;
    }

    public synchronized boolean isDirty() {
        return savedDraft == null || !draft.equals(savedDraft);
    }

    public synchronized boolean canUndo() {
        return !undoHistory.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redoHistory.isEmpty();
    }

    public synchronized int undoDepth() {
        return undoHistory.size();
    }

    public synchronized int redoDepth() {
        return redoHistory.size();
    }

    public synchronized void markSaved() {
        savedDraft = draft;
    }

    public synchronized boolean resize(SimpleStructureStudioLayout newLayout) {
        Objects.requireNonNull(newLayout, "newLayout");
        if (draft.layout().equals(newLayout)) {
            return false;
        }
        if (draft.hasContent()) {
            throw new IllegalStateException("The studio layout cannot be resized after content has been added");
        }
        return applyDraft(draft.withLayout(newLayout));
    }

    public synchronized boolean replaceCell(SimpleStructureStudioCell cell) {
        return applyDraft(draft.withCell(Objects.requireNonNull(cell, "cell")));
    }

    public synchronized boolean clearCell(int x, int z) {
        return applyDraft(draft.withoutCell(x, z));
    }

    public synchronized boolean setTopology(int x, int z, SimpleStructureStudioTopology topology) {
        Objects.requireNonNull(topology, "topology");
        if (topology == SimpleStructureStudioTopology.EMPTY) {
            return clearCell(x, z);
        }
        SimpleStructureStudioCell cell = draft.cellOrEmpty(x, z).withTopology(topology);
        return applyDraft(draft.withCell(cell));
    }

    public synchronized boolean setQuarterTurns(int x, int z, int quarterTurns) {
        return updatePopulatedCell(x, z, cell -> cell.withQuarterTurns(quarterTurns));
    }

    public synchronized boolean rotateClockwise(int x, int z) {
        return updatePopulatedCell(x, z, SimpleStructureStudioCell::rotateClockwise);
    }

    public synchronized boolean rotateCounterClockwise(int x, int z) {
        return updatePopulatedCell(x, z, SimpleStructureStudioCell::rotateCounterClockwise);
    }

    public synchronized boolean setRotationPolicy(
            int x,
            int z,
            SimpleStructureStudioRotationPolicy rotationPolicy
    ) {
        Objects.requireNonNull(rotationPolicy, "rotationPolicy");
        return updatePopulatedCell(x, z, cell -> cell.withRotationPolicy(rotationPolicy));
    }

    public synchronized boolean setConnector(int x, int z, String channel, int height) {
        return updatePopulatedCell(x, z, cell -> cell.withConnector(channel, height));
    }

    public synchronized boolean addVariant(int x, int z, SimpleStructureStudioVariant variant) {
        Objects.requireNonNull(variant, "variant");
        return updatePopulatedCell(x, z, cell -> cell.addVariant(variant));
    }

    public synchronized boolean setVariantWeight(int x, int z, String variantId, int weight) {
        return updatePopulatedCell(x, z, cell -> cell.setVariantWeight(variantId, weight));
    }

    public synchronized boolean removeVariant(int x, int z, String variantId) {
        return updatePopulatedCell(x, z, cell -> cell.removeVariant(variantId));
    }

    public synchronized boolean selectVariant(int x, int z, String variantId) {
        return updatePopulatedCell(x, z, cell -> cell.selectVariant(variantId));
    }

    public synchronized boolean cycleVariant(int x, int z, int offset) {
        return updatePopulatedCell(x, z, cell -> cell.cycleVariant(offset));
    }

    public synchronized boolean setPreviewSeed(long previewSeed) {
        return applyDraft(draft.withPreviewSeed(previewSeed));
    }

    public synchronized long advancePreviewSeed() {
        long nextSeed = nextPreviewSeed(draft.previewSeed());
        applyDraft(draft.withPreviewSeed(nextSeed));
        return nextSeed;
    }

    public synchronized boolean undo() {
        if (undoHistory.isEmpty()) {
            return false;
        }
        redoHistory.addLast(draft);
        trimHistory(redoHistory);
        draft = undoHistory.removeLast();
        return true;
    }

    public synchronized boolean redo() {
        if (redoHistory.isEmpty()) {
            return false;
        }
        undoHistory.addLast(draft);
        trimHistory(undoHistory);
        draft = redoHistory.removeLast();
        return true;
    }

    public static long nextPreviewSeed(long previewSeed) {
        return previewSeed + PREVIEW_SEED_STEP;
    }

    private boolean updatePopulatedCell(
            int x,
            int z,
            UnaryOperator<SimpleStructureStudioCell> update
    ) {
        SimpleStructureStudioCell cell = draft.cellAt(x, z).orElseThrow(
                () -> new IllegalStateException("Studio cell is empty: " + x + ", " + z)
        );
        SimpleStructureStudioCell updatedCell = Objects.requireNonNull(update.apply(cell), "updatedCell");
        if (updatedCell.x() != x || updatedCell.z() != z) {
            throw new IllegalArgumentException("Cell updates cannot change the cell position");
        }
        return applyDraft(draft.withCell(updatedCell));
    }

    private boolean applyDraft(SimpleStructureStudioDraft updatedDraft) {
        Objects.requireNonNull(updatedDraft, "updatedDraft");
        if (draft.equals(updatedDraft)) {
            return false;
        }
        undoHistory.addLast(draft);
        trimHistory(undoHistory);
        draft = updatedDraft;
        redoHistory.clear();
        return true;
    }

    private void trimHistory(Deque<SimpleStructureStudioDraft> history) {
        while (history.size() > historyLimit) {
            history.removeFirst();
        }
    }
}
