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
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.iris.engine.object.annotations.Required;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("A jigsaw connection point on a piece. When two connectors face each other and their names match, the assembler may join the pieces.")
@Data
public class IrisJigsawConnector {
    @Required
    @Desc("The position of this connector relative to the piece object's origin (0,0,0 is the lowest-corner of the object).")
    private IrisPosition position = new IrisPosition();

    @Required
    @Desc("The direction this connector faces. The connecting piece is placed on this side.")
    private IrisDirection direction = IrisDirection.NORTH_NEGATIVE_Z;

    @Required
    @Desc("The connector's authored top direction, used to preserve aligned vanilla jigsaw orientation.")
    private IrisDirection top = IrisDirection.UP_POSITIVE_Y;

    @Required
    @RegistryListResource(IrisJigsawPool.class)
    @Desc("The jigsaw pool to draw the connecting piece from.")
    private String pool = "";

    @Desc("The identity exposed by this connector. Another connector can attach when its targetName equals this name, matching Minecraft's jigsaw target-to-name contract.")
    private String name = "";

    @Desc("The name this connector wants to connect to on the other piece.")
    private String targetName = "";

    @Desc("How the connecting piece may be rotated relative to this connector.")
    private JigsawJoint joint = JigsawJoint.ROLLABLE;
}
