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

import java.util.Locale;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("Controls native vanilla, mod, and ingested datapack structure generation for this dimension (set as the dimension's 'importedStructures' field). Default mode is ALL_ON. Blacklist keys with 'disabled', or use ALL_OFF with 'enabled' as a whitelist. Family matching uses namespace, slash, or underscore boundaries, so 'minecraft:village' covers every village variant without matching unrelated names. Run '/iris structure list <dimension>' to dump every valid key. Only affects newly generated chunks and is separate from Iris structure placements.")
@Data
public class IrisImportedStructureControl {
    @Desc("Master toggle. ALL_ON generates every native structure except those in 'disabled'. ALL_OFF generates nothing except those in 'enabled'.")
    private VanillaStructureMode mode = VanillaStructureMode.ALL_ON;

    @ArrayType(type = String.class, min = 1)
    @RegistryListVanillaStructure
    @Desc("Structure keys to turn OFF while mode is ALL_ON, e.g. 'minecraft:stronghold'. A namespace:path prefix also matches, so 'minecraft:village' disables every village variant and 'minecraft:ruined_portal' disables every ruined portal. Ignored when mode is ALL_OFF.")
    private KList<String> disabled = new KList<>();

    @ArrayType(type = String.class, min = 1)
    @RegistryListVanillaStructure
    @Desc("Structure keys to turn ON while mode is ALL_OFF, e.g. 'minecraft:village_plains'. A family prefix also matches at namespace, slash, or underscore boundaries, so 'minecraft:village' enables every village variant. Ignored when mode is ALL_ON.")
    private KList<String> enabled = new KList<>();

    @MinNumber(-512)
    @MaxNumber(512)
    @Desc("Vertical block offset applied only to UNDERGROUND vanilla structures (the UNDERGROUND_STRUCTURES, UNDERGROUND_DECORATION, and STRONGHOLDS generation steps: strongholds, trial chambers, mineshafts, ancient cities, etc.). Surface structures (villages, outposts, etc.) are never shifted. Use a negative value to push deep structures lower when your dimension's sea/terrain level differs from vanilla's (e.g. -64 if you lowered the fluid height to 0). 0 = no shift.")
    private int undergroundYShift = 0;

    @Desc("Controls whether ingested datapacks may replace minecraft-namespaced structure definitions, sets, pools, and templates. When false, those overrides are stripped from installed datapack copies so vanilla definitions stay intact. Non-minecraft structures from datapacks and mods remain governed by mode, enabled, and disabled because namespace alone cannot identify their origin. Resolved globally across loaded packs: if any dimension sets this false, minecraft-namespaced overrides are stripped from every installed datapack copy.")
    private boolean datapackOverrides = true;

    @ArrayType(type = IrisVanillaStructureAdjustment.class, min = 1)
    @Desc("Per-structure adjustments applied to vanilla, mod, and datapack structures that still generate natively. Vertical shifts from every matching entry stack. Surface-intersecting structures clear logs and leaves automatically; a matching vegetation option can force clearing for unusual placements. The last matching entry with stilt settings controls foundation columns. A structure suppressed by an Iris placement is unaffected.")
    private KList<IrisVanillaStructureAdjustment> adjustments = new KList<>();

    public boolean active() {
        return mode == VanillaStructureMode.ALL_ON || !enabled.isEmpty();
    }

    public boolean shouldGenerate(String key) {
        if (mode == VanillaStructureMode.ALL_ON) {
            return !matches(disabled, key);
        }
        return matches(enabled, key);
    }

    public IrisNativeStructureDecision resolve(String key, boolean undergroundStep) {
        int y = undergroundStep ? undergroundYShift : 0;
        boolean clearVegetation = false;
        IrisVanillaStructureStiltSettings stilt = null;
        for (IrisVanillaStructureAdjustment adjustment : adjustments) {
            if (adjustment != null && adjustment.matches(key)) {
                y += adjustment.getYShift();
                clearVegetation |= adjustment.isClearVegetation();
                if (adjustment.getStilt() != null) {
                    stilt = adjustment.getStilt();
                }
            }
        }
        return new IrisNativeStructureDecision(shouldGenerate(key), y, clearVegetation, stilt);
    }

    private boolean matches(KList<String> list, String key) {
        if (key == null) {
            return false;
        }
        for (String entry : list) {
            if (matchesKey(entry, key)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesKey(String pattern, String key) {
        if (pattern == null || key == null) {
            return false;
        }
        String normalizedPattern = pattern.trim().toLowerCase(Locale.ROOT);
        String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
        if (normalizedPattern.isEmpty() || !normalizedKey.startsWith(normalizedPattern)) {
            return false;
        }
        if (normalizedKey.length() == normalizedPattern.length()) {
            return true;
        }
        char patternEnd = normalizedPattern.charAt(normalizedPattern.length() - 1);
        if (patternEnd == ':' || patternEnd == '/' || patternEnd == '_') {
            return true;
        }
        char boundary = normalizedKey.charAt(normalizedPattern.length());
        return boundary == '/' || boundary == '_';
    }
}
