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

package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListStructure;
import art.arcane.volmlib.util.collection.KList;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Attaches structures to a biome, region or dimension and controls where and how often they generate. This is independent of a structure's own native generation, so you can add custom placements in tandem with native generation or instead of it.")
@Data
public class IrisStructurePlacement {
    @ArrayType(type = String.class, min = 1)
    @RegistryListStructure
    @Desc("Editable Iris assembly resources offered by this placement. Leave empty when nativeStructures supplies the source.")
    private KList<String> structures = new KList<>();

    @ArrayType(type = IrisNativeStructure.class, min = 1)
    @Desc("Live registered vanilla, datapack, and modded structures offered by this placement. Minecraft runs their native generator directly, independent of normal biome or dimension eligibility.")
    private KList<IrisNativeStructure> nativeStructures = new KList<>();

    @Desc("Stable authored identity for this placement. Set this when multiple placements use the same structure and settings. When empty, Iris derives identity from the placement content so list reordering does not move generated structures.")
    private String placementId = "";

    @Desc("Controls native replacement for this placement. REPLACE_SOURCE suppresses each selected Iris structure's vanillaSource or registered native source, and is honored only for dimension-level placements. NONE leaves native generation untouched.")
    private NativeStructureSuppression nativeSuppression = NativeStructureSuppression.NONE;

    @Desc("How start positions are scattered.")
    private StructureDistribution distribution = StructureDistribution.RANDOM_SPREAD;

    @MinNumber(1)
    @MaxNumber(4096)
    @Desc("RANDOM_SPREAD only: the grid cell size in chunks. One placement attempt per cell. Larger = rarer.")
    private int spacing = 32;

    @MinNumber(0)
    @Desc("RANDOM_SPREAD only: the minimum chunk separation between placements within the grid. Must be smaller than spacing.")
    private int separation = 8;

    @Desc("RANDOM_SPREAD only: a salt mixed into the placement RNG so different structures using the same spacing do not stack.")
    private int salt = 165745296;

    @MinNumber(0)
    @MaxNumber(1)
    @Desc("DENSITY only: the per-chunk probability (0-1) of a placement starting in a chunk.")
    private double density = 0.02;

    @MinNumber(1)
    @Desc("CONCENTRIC_RINGS only: the number of placements distributed across the rings.")
    private int ringCount = 128;

    @MinNumber(1)
    @Desc("CONCENTRIC_RINGS only: the ring spacing in chunks.")
    private int ringDistance = 32;

    @MinNumber(1)
    @Desc("CONCENTRIC_RINGS only: how many placements share each ring before moving outward.")
    private int ringSpread = 3;

    @Desc("When underground=false this is the minimum surface Y the placement is allowed at (a gate); when underground=true this is the lower bound of the Y band the structure is placed within.")
    private int minHeight = -2032;

    @Desc("When underground=false this is the maximum surface Y the placement is allowed at (a gate); when underground=true this is the upper bound of the Y band the structure is placed within.")
    private int maxHeight = 2032;

    @Desc("If true, the structure starts at a deterministic random world Y inside [minHeight, maxHeight]. Terrain integration is then controlled independently by terrain.")
    private boolean underground = false;

    @Desc("Terrain integration for this placement. The editable structures backend supports SOURCE, PRESERVE, BORE, and FORCE_CARVE. The nativeStructures backend supports every terrain mode.")
    private IrisStructureTerrain terrain = new IrisStructureTerrain();

    @Desc("Optional foundation columns placed beneath the assembled structure's occupied bottom cells. Columns pass through air and fluids until they reach solid ground, up to maxDepth.")
    private IrisStructureStiltSettings stilt = null;

    @Desc("If false, this placement is skipped underwater.")
    private boolean underwater = false;

    public IrisStructureTerrain resolvedTerrain() {
        return terrain == null ? new IrisStructureTerrain() : terrain;
    }

    public boolean hasIrisStructures() {
        return structures != null && !structures.isEmpty();
    }

    public boolean hasNativeStructures() {
        return nativeStructures != null && !nativeStructures.isEmpty();
    }
}
