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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record SimpleStructureStudioDraft(
        SimpleStructureStudioLayout layout,
        long previewSeed,
        List<SimpleStructureStudioCell> cells
) {
    private static final Comparator<SimpleStructureStudioCell> CELL_ORDER = Comparator
            .comparingInt(SimpleStructureStudioCell::z)
            .thenComparingInt(SimpleStructureStudioCell::x);

    public SimpleStructureStudioDraft {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(cells, "cells");
        List<SimpleStructureStudioCell> orderedCells = new ArrayList<>(cells);
        orderedCells.sort(CELL_ORDER);
        validateCells(layout, orderedCells);
        cells = List.copyOf(orderedCells);
    }

    public static SimpleStructureStudioDraft empty(SimpleStructureStudioLayout layout, long previewSeed) {
        return new SimpleStructureStudioDraft(layout, previewSeed, List.of());
    }

    public boolean hasContent() {
        return !cells.isEmpty();
    }

    public Optional<SimpleStructureStudioCell> cellAt(int x, int z) {
        requirePosition(x, z);
        for (SimpleStructureStudioCell cell : cells) {
            if (cell.x() == x && cell.z() == z) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    public SimpleStructureStudioCell cellOrEmpty(int x, int z) {
        return cellAt(x, z).orElseGet(() -> SimpleStructureStudioCell.empty(x, z));
    }

    public SimpleStructureStudioDraft withLayout(SimpleStructureStudioLayout newLayout) {
        Objects.requireNonNull(newLayout, "newLayout");
        if (hasContent() && !layout.equals(newLayout)) {
            throw new IllegalStateException("The studio layout cannot be resized after content has been added");
        }
        return new SimpleStructureStudioDraft(newLayout, previewSeed, cells);
    }

    public SimpleStructureStudioDraft withPreviewSeed(long newPreviewSeed) {
        return new SimpleStructureStudioDraft(layout, newPreviewSeed, cells);
    }

    public SimpleStructureStudioDraft withCell(SimpleStructureStudioCell updatedCell) {
        Objects.requireNonNull(updatedCell, "updatedCell");
        requirePosition(updatedCell.x(), updatedCell.z());
        List<SimpleStructureStudioCell> updatedCells = new ArrayList<>(cells.size() + 1);
        for (SimpleStructureStudioCell cell : cells) {
            if (cell.x() != updatedCell.x() || cell.z() != updatedCell.z()) {
                updatedCells.add(cell);
            }
        }
        if (!updatedCell.isEmpty()) {
            updatedCells.add(updatedCell);
        }
        return new SimpleStructureStudioDraft(layout, previewSeed, updatedCells);
    }

    public SimpleStructureStudioDraft withoutCell(int x, int z) {
        requirePosition(x, z);
        List<SimpleStructureStudioCell> updatedCells = new ArrayList<>(cells.size());
        for (SimpleStructureStudioCell cell : cells) {
            if (cell.x() != x || cell.z() != z) {
                updatedCells.add(cell);
            }
        }
        return new SimpleStructureStudioDraft(layout, previewSeed, updatedCells);
    }

    private static void validateCells(
            SimpleStructureStudioLayout layout,
            List<SimpleStructureStudioCell> cells
    ) {
        Set<Long> positions = new HashSet<>();
        for (SimpleStructureStudioCell cell : cells) {
            Objects.requireNonNull(cell, "cell");
            if (cell.isEmpty()) {
                throw new IllegalArgumentException("Drafts store only populated cells");
            }
            if (!layout.contains(cell.x(), cell.z())) {
                throw new IllegalArgumentException(
                        "Cell is outside the studio grid: " + cell.x() + ", " + cell.z()
                );
            }
            if (cell.connectorHeight() >= layout.captureHeight()) {
                throw new IllegalArgumentException(
                        "Connector height " + cell.connectorHeight()
                                + " is outside capture height " + layout.captureHeight()
                );
            }
            long position = ((long) cell.x() << 32) ^ (cell.z() & 0xffffffffL);
            if (!positions.add(position)) {
                throw new IllegalArgumentException("Duplicate studio cell: " + cell.x() + ", " + cell.z());
            }
        }
    }

    private void requirePosition(int x, int z) {
        if (!layout.contains(x, z)) {
            throw new IndexOutOfBoundsException("Cell is outside the studio grid: " + x + ", " + z);
        }
    }
}
