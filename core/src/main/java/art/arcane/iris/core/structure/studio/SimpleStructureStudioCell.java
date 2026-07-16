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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public record SimpleStructureStudioCell(
        int x,
        int z,
        SimpleStructureStudioTopology topology,
        int quarterTurns,
        SimpleStructureStudioRotationPolicy rotationPolicy,
        String connectorChannel,
        int connectorHeight,
        List<SimpleStructureStudioVariant> variants,
        int activeVariantIndex
) {
    public static final String DEFAULT_CONNECTOR_CHANNEL = "path";

    private static final Pattern CONNECTOR_CHANNEL_PATTERN = Pattern.compile(
            "(?:[a-z0-9._-]+:)?[a-z0-9._-]+(?:/[a-z0-9._-]+)*"
    );

    public SimpleStructureStudioCell {
        if (x < 0 || z < 0) {
            throw new IllegalArgumentException("Cell coordinates cannot be negative: " + x + ", " + z);
        }
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(rotationPolicy, "rotationPolicy");
        quarterTurns = Math.floorMod(quarterTurns, 4);
        if (!rotationPolicy.allows(quarterTurns)) {
            throw new IllegalArgumentException(
                    "Rotation policy " + rotationPolicy + " does not allow quarter turn " + quarterTurns
            );
        }
        Objects.requireNonNull(connectorChannel, "connectorChannel");
        if (!CONNECTOR_CHANNEL_PATTERN.matcher(connectorChannel).matches()) {
            throw new IllegalArgumentException("Invalid connector channel: " + connectorChannel);
        }
        if (connectorHeight < 0) {
            throw new IllegalArgumentException("Connector height cannot be negative: " + connectorHeight);
        }
        Objects.requireNonNull(variants, "variants");
        variants = List.copyOf(variants);
        validateVariants(variants);
        if (topology == SimpleStructureStudioTopology.EMPTY && !variants.isEmpty()) {
            throw new IllegalArgumentException("Empty cells cannot contain variants");
        }
        if (variants.isEmpty() && activeVariantIndex != -1) {
            throw new IllegalArgumentException("A cell without variants must use activeVariantIndex -1");
        }
        if (!variants.isEmpty() && (activeVariantIndex < 0 || activeVariantIndex >= variants.size())) {
            throw new IllegalArgumentException("Active variant index is outside the variant list: " + activeVariantIndex);
        }
    }

    public static SimpleStructureStudioCell empty(int x, int z) {
        return new SimpleStructureStudioCell(
                x,
                z,
                SimpleStructureStudioTopology.EMPTY,
                0,
                SimpleStructureStudioRotationPolicy.FIXED,
                DEFAULT_CONNECTOR_CHANNEL,
                0,
                List.of(),
                -1
        );
    }

    public static SimpleStructureStudioCell create(int x, int z, SimpleStructureStudioTopology topology) {
        Objects.requireNonNull(topology, "topology");
        if (topology == SimpleStructureStudioTopology.EMPTY) {
            return empty(x, z);
        }
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                0,
                SimpleStructureStudioRotationPolicy.QUARTER_TURNS,
                DEFAULT_CONNECTOR_CHANNEL,
                0,
                List.of(),
                -1
        );
    }

    public boolean isEmpty() {
        return topology == SimpleStructureStudioTopology.EMPTY;
    }

    public int connectorMask() {
        return topology.connectorMask(quarterTurns);
    }

    public boolean connects(SimpleStructureStudioDirection direction) {
        return topology.connects(direction, quarterTurns);
    }

    public Optional<SimpleStructureStudioVariant> activeVariant() {
        if (activeVariantIndex < 0) {
            return Optional.empty();
        }
        return Optional.of(variants.get(activeVariantIndex));
    }

    public SimpleStructureStudioCell withTopology(SimpleStructureStudioTopology newTopology) {
        Objects.requireNonNull(newTopology, "newTopology");
        if (newTopology == SimpleStructureStudioTopology.EMPTY) {
            return empty(x, z);
        }
        if (isEmpty()) {
            return create(x, z, newTopology);
        }
        return new SimpleStructureStudioCell(
                x,
                z,
                newTopology,
                quarterTurns,
                rotationPolicy,
                connectorChannel,
                connectorHeight,
                variants,
                activeVariantIndex
        );
    }

    public SimpleStructureStudioCell withQuarterTurns(int newQuarterTurns) {
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                newQuarterTurns,
                rotationPolicy,
                connectorChannel,
                connectorHeight,
                variants,
                activeVariantIndex
        );
    }

    public SimpleStructureStudioCell rotateClockwise() {
        return withQuarterTurns(rotationPolicy.next(quarterTurns));
    }

    public SimpleStructureStudioCell rotateCounterClockwise() {
        return withQuarterTurns(rotationPolicy.previous(quarterTurns));
    }

    public SimpleStructureStudioCell withRotationPolicy(SimpleStructureStudioRotationPolicy newPolicy) {
        Objects.requireNonNull(newPolicy, "newPolicy");
        int newQuarterTurns = newPolicy.allows(quarterTurns) ? quarterTurns : 0;
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                newQuarterTurns,
                newPolicy,
                connectorChannel,
                connectorHeight,
                variants,
                activeVariantIndex
        );
    }

    public SimpleStructureStudioCell withConnector(String newChannel, int newHeight) {
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                quarterTurns,
                rotationPolicy,
                newChannel,
                newHeight,
                variants,
                activeVariantIndex
        );
    }

    public SimpleStructureStudioCell addVariant(SimpleStructureStudioVariant variant) {
        Objects.requireNonNull(variant, "variant");
        List<SimpleStructureStudioVariant> updatedVariants = new ArrayList<>(variants);
        updatedVariants.add(variant);
        int newActiveIndex = activeVariantIndex < 0 ? 0 : activeVariantIndex;
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                quarterTurns,
                rotationPolicy,
                connectorChannel,
                connectorHeight,
                updatedVariants,
                newActiveIndex
        );
    }

    public SimpleStructureStudioCell setVariantWeight(String variantId, int weight) {
        int variantIndex = requireVariantIndex(variantId);
        List<SimpleStructureStudioVariant> updatedVariants = new ArrayList<>(variants);
        updatedVariants.set(variantIndex, updatedVariants.get(variantIndex).withWeight(weight));
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                quarterTurns,
                rotationPolicy,
                connectorChannel,
                connectorHeight,
                updatedVariants,
                activeVariantIndex
        );
    }

    public SimpleStructureStudioCell removeVariant(String variantId) {
        int variantIndex = requireVariantIndex(variantId);
        List<SimpleStructureStudioVariant> updatedVariants = new ArrayList<>(variants);
        updatedVariants.remove(variantIndex);
        int newActiveIndex = activeVariantIndex;
        if (updatedVariants.isEmpty()) {
            newActiveIndex = -1;
        } else if (variantIndex < activeVariantIndex) {
            newActiveIndex--;
        } else if (newActiveIndex >= updatedVariants.size()) {
            newActiveIndex = updatedVariants.size() - 1;
        }
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                quarterTurns,
                rotationPolicy,
                connectorChannel,
                connectorHeight,
                updatedVariants,
                newActiveIndex
        );
    }

    public SimpleStructureStudioCell selectVariant(String variantId) {
        int variantIndex = requireVariantIndex(variantId);
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                quarterTurns,
                rotationPolicy,
                connectorChannel,
                connectorHeight,
                variants,
                variantIndex
        );
    }

    public SimpleStructureStudioCell cycleVariant(int offset) {
        if (variants.isEmpty()) {
            return this;
        }
        int newActiveIndex = Math.floorMod((long) activeVariantIndex + offset, variants.size());
        return new SimpleStructureStudioCell(
                x,
                z,
                topology,
                quarterTurns,
                rotationPolicy,
                connectorChannel,
                connectorHeight,
                variants,
                newActiveIndex
        );
    }

    private static void validateVariants(List<SimpleStructureStudioVariant> variants) {
        Set<String> variantIds = new HashSet<>();
        for (SimpleStructureStudioVariant variant : variants) {
            Objects.requireNonNull(variant, "variant");
            if (!variantIds.add(variant.id())) {
                throw new IllegalArgumentException("Duplicate variant id: " + variant.id());
            }
        }
    }

    private int requireVariantIndex(String variantId) {
        Objects.requireNonNull(variantId, "variantId");
        for (int i = 0; i < variants.size(); i++) {
            if (variants.get(i).id().equals(variantId)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown variant id: " + variantId);
    }
}
