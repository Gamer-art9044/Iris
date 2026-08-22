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

package art.arcane.iris.util.common.director;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.director.DirectorSystemSupport;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.io.JarScanner;
public final class DirectorSystem {
    public static final KList<art.arcane.volmlib.util.director.DirectorParameterHandler<?>> handlers = initializePackage("art.arcane.iris.util.common.director.handlers").convert((i) -> (art.arcane.volmlib.util.director.DirectorParameterHandler<?>) i);

    private DirectorSystem() {
    }

    public static KList<Object> initializePackage(String packageName) {
        JarScanner js = new JarScanner(IrisPlatforms.get().pluginJar(), packageName);
        KList<Object> v = new KList<>();
        J.attempt(js::scan);
        for (Class<?> i : js.getClasses()) {
            try {
                v.add(i.getDeclaredConstructor().newInstance());
            } catch (Throwable ex) {
                IrisLogging.warn("Skipped class initialization for %s: %s%s",
                        i.getName(),
                        ex.getClass().getSimpleName(),
                        ex.getMessage() == null ? "" : " - " + ex.getMessage());
                IrisLogging.reportError(ex);
            }
        }

        return v;
    }

    /**
     * Get the handler for the specified type
     *
     * @param type The type to handle
     * @return The corresponding {@link DirectorParameterHandler}, or null
     */
    public static art.arcane.volmlib.util.director.DirectorParameterHandler<?> getHandler(Class<?> type) {
        art.arcane.volmlib.util.director.DirectorParameterHandler<?> handler = DirectorSystemSupport.getHandler(handlers, type, (h, t) -> h.supports(t));
        if (handler != null) {
            return handler;
        }

        IrisLogging.error("Unhandled type in Director Parameter: " + type.getName() + ". This is bad!");
        return null;
    }
}
