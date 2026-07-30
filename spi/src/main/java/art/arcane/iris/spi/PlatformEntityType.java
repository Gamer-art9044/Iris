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
 * Neutral handle for a resolved entity type backed by an adapter-owned native handle.
 * <p>
 * Immutable and safe to share across threads. Internal to Iris; not a published integration surface.
 *
 * @see PlatformRegistries#entity(String)
 */
public interface PlatformEntityType {
    /**
     * Canonical {@code namespace:path} entity type key. Never null.
     */
    String key();

    /**
     * Namespace half of {@link #key()}. Never null.
     */
    String namespace();

    /**
     * The host's spawn category, lowercased - {@code monster}, {@code creature}, {@code ambient} and so on.
     * Iris matches it against pack spawn rules, so the string form is the contract rather than any enum. Never
     * null.
     */
    String spawnCategory();

    /**
     * The adapter's backing entity type object - {@code org.bukkit.entity.EntityType} on Bukkit,
     * {@code EntityType} on a mod loader. Never null. Only code inside the owning adapter may cast it.
     */
    Object nativeHandle();
}
