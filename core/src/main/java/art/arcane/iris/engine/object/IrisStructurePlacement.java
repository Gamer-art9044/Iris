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
    @Desc("Editable Iris structure resources to place here. Every key must resolve to a structures/*.json resource in this pack. Live vanilla, mod, and datapack registry keys are controlled through importedStructures unless explicitly cloned into Iris resources.")
    private KList<String> structures = new KList<>();

    @Desc("Stable authored identity for this placement. Set this when multiple placements use the same structure and settings. When empty, Iris derives identity from the placement content so list reordering does not move generated structures.")
    private String placementId = "";

    @Desc("Controls native replacement for this placement. REPLACE_SOURCE suppresses each selected Iris structure's vanillaSource, and is honored only for dimension-level placements. NONE leaves native generation untouched.")
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

    @Desc("if true the structure is placed underground at a random world Y inside [minHeight, maxHeight] (raw stamp, no terrain matching) instead of being dropped onto the terrain surface. Use this for deep structures like ancient cities in a deep cave band.")
    private boolean underground = false;

    @Desc("if true, the structure's full bounding box (floor up to roof) is bored out to air before the pieces are stamped, so the structure sits inside an open cavern instead of being encased in solid terrain. Essential for underground structures such as ancient cities to be visible and enterable.")
    private boolean bore = false;

    @Desc("extra blocks of air clearance added around the bored bounding box (horizontally and above) when bore=true. The floor is never bored below the structure so support is preserved.")
    private int borePadding = 0;

    @Desc("if true, massively carves the surrounding terrain into an open cavern around the structure instead of only clearing its tight bounding box. The structure's full interior is cleared and the surrounding terrain is excavated out to overboreRadius blocks, doming down to meet the ground at the edges so the structure is no longer buried in solid terrain. The mantle write window and the carve volume cap are expanded automatically to fit. Use for deep structures such as ancient cities so both their interior and surroundings are open and enterable. Takes precedence over bore when both are set.")
    private boolean overbore = false;

    @MinNumber(0)
    @MaxNumber(128)
    @Desc("when overbore=true, how many blocks of terrain to carve horizontally outward from the structure's bounding box. Larger values produce a wider open cavern around the structure. Larger values also widen the mantle write window for every chunk near the structure, so increase it deliberately.")
    private int overboreRadius = 24;

    @MinNumber(0)
    @MaxNumber(128)
    @Desc("when overbore=true, how many extra blocks of air to carve above the structure's roof at the cavern apex.")
    private int overboreHeight = 8;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("when overbore=true, how many blocks of terrain to carve below the structure's floor. 0 keeps the floor solid for support; small values recess the cavern floor around the structure.")
    private int overboreFloor = 0;

    @Desc("Optional foundation columns placed beneath the assembled structure's occupied bottom cells. Columns pass through air and fluids until they reach solid ground, up to maxDepth.")
    private IrisStructureStiltSettings stilt = null;

    @Desc("If false, this placement is skipped underwater.")
    private boolean underwater = false;
}
