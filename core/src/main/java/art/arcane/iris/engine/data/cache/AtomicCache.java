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

package art.arcane.iris.engine.data.cache;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.function.NastySupplier;

import java.util.function.Supplier;

public class AtomicCache<T> {
    private static final Object NULL_VALUE = new Object();
    private transient final Object initLock = new Object();
    private transient final boolean nullSupport;
    private transient volatile Object value;

    public AtomicCache() {
        this(false);
    }

    public AtomicCache(boolean nullSupport) {
        this.nullSupport = nullSupport;
    }

    public void reset() {
        synchronized (initLock) {
            value = null;
        }
    }

    public T getIfPresent() {
        return unwrap(value);
    }

    public T aquireNasty(NastySupplier<T> t) {
        return aquire(() -> {
            try {
                return t.get();
            } catch (Throwable e) {
                return null;
            }
        });
    }

    public T aquireNastyPrint(NastySupplier<T> t) {
        return aquire(() -> {
            try {
                return t.get();
            } catch (Throwable e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    /**
     * Like {@link #aquire(Supplier)} but propagates a supplier failure to the caller instead
     * of swallowing it into a null return. For values that are mandatory: a caller of a
     * "@NotNull" accessor should see the supplier's real exception, not a downstream NPE.
     */
    public T aquireOrThrow(Supplier<T> t) {
        Object v = value;

        if (v != null) {
            return unwrap(v);
        }

        synchronized (initLock) {
            v = value;

            if (v != null) {
                return unwrap(v);
            }

            T computed = t.get();
            if (computed == null) {
                throw new IllegalStateException("Atomic cache supplier produced null");
            }
            value = computed;
            return computed;
        }
    }

    public T aquire(Supplier<T> t) {
        Object v = value;

        if (v != null) {
            return unwrap(v);
        }

        synchronized (initLock) {
            v = value;

            if (v != null) {
                return unwrap(v);
            }

            try {
                T computed = t.get();

                if (computed != null) {
                    value = computed;
                    return computed;
                }

                if (nullSupport) {
                    value = NULL_VALUE;
                }
            } catch (Throwable e) {
                IrisLogging.error("Atomic cache failure!");
                e.printStackTrace();
            }

            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private T unwrap(Object v) {
        return v == null || v == NULL_VALUE ? null : (T) v;
    }
}
