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

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("A weighted reference to a jigsaw piece inside a jigsaw pool.")
@Data
public class IrisJigsawPieceEntry {
    @RegistryListResource(IrisJigsawPiece.class)
    @Desc("The jigsaw piece this entry refers to. Leave empty only when empty is true.")
    private String piece = "";

    @MinNumber(1)
    @Desc("The relative weight of this piece within its pool. Higher weights are chosen more often.")
    private int weight = 1;

    @Desc("When true, choosing this entry successfully terminates the current branch without placing a piece.")
    private boolean empty = false;

    public IrisJigsawPieceEntry(String piece, int weight) {
        this(piece, weight, false);
    }
}
