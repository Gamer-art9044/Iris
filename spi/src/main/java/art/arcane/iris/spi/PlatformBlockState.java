/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

package art.arcane.iris.spi;

/**
 * Neutral handle for a resolved block state; the canonical key is the config currency and the native handle is adapter-owned.
 */
public interface PlatformBlockState {
    String key();

    String namespace();

    boolean isAir();

    boolean isSolid();

    boolean isOccluding();

    boolean isCustom();

    default String deferredPlacementKey() {
        return null;
    }

    default PlatformBlockState placementBaseState() {
        return this;
    }

    boolean isFluid();

    boolean isWater();

    boolean isWaterLogged();

    boolean isLit();

    boolean isUpdatable();

    boolean isFoliage();

    boolean isTreeBlock();

    boolean isFoliagePlantable();

    boolean isDecorant();

    boolean isStorage();

    boolean isStorageChest();

    boolean isOre();

    boolean isDeepSlate();

    boolean isVineBlock();

    boolean canPlaceOnto(PlatformBlockState onto);

    boolean matches(PlatformBlockState state);

    default boolean isAirOrFluid() {
        return isAir() || isFluid();
    }

    boolean hasTileEntity();

    PlatformBlockState withProperty(String name, String value);

    Object nativeHandle();
}
