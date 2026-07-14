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
import art.arcane.iris.engine.object.annotations.RegistryListVanillaStructure;
import art.arcane.volmlib.util.collection.KList;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("A per-structure adjustment applied to vanilla, mod, and datapack structures that still generate natively (those NOT suppressed by an Iris 'structures' placement). Vertical shifts move the structure start, pieces, bounds, and jigsaw metadata together before references and placement. Surface structures clear intersecting trees automatically; optional postprocessing can force vegetation clearing or build palette-driven foundation columns.")
@Data
public class IrisVanillaStructureAdjustment {
    @ArrayType(type = String.class, min = 1)
    @RegistryListVanillaStructure
    @Desc("Structure keys this adjustment applies to, e.g. 'minecraft:stronghold'. A namespace:path prefix also matches, so 'minecraft:village' adjusts every village variant and 'minecraft:ruined_portal' adjusts every ruined portal. Empty matches nothing.")
    private KList<String> match = new KList<>();

    @MinNumber(-512)
    @MaxNumber(512)
    @Desc("Vertical block offset. Negative pushes the structure down, positive lifts it. The resolved shift is clamped so the structure remains inside the world's vertical build bounds.")
    private int yShift = 0;

    @Desc("When true, force logs and leaves out of the structure footprint even when the structure does not reach the detected tree base. Normal surface-intersecting structures are protected automatically.")
    private boolean clearVegetation = false;

    @Desc("Optional foundation columns placed beneath the native structure piece bases after placement.")
    private IrisVanillaStructureStiltSettings stilt = null;

    public boolean matches(String key) {
        for (String entry : match) {
            if (IrisImportedStructureControl.matchesKey(entry, key)) {
                return true;
            }
        }
        return false;
    }
}
