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

package art.arcane.iris.engine;

import art.arcane.iris.engine.EngineBackgroundTasks.BackgroundTaskDrain;
import art.arcane.iris.engine.EngineRuntimeBuilder.RuntimeAssembly;
import art.arcane.iris.engine.IrisEngine.LifecycleState;
import art.arcane.iris.engine.framework.GenerationSessionException;
import art.arcane.iris.engine.framework.NativeStructureOwnershipStore;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;

/**
 * Owns the ordered teardown of a single {@link IrisEngine}, both for a normal close and for a
 * construction that failed part way through. Every resource release is gated on the previous one
 * having succeeded so a partially released engine never reports itself as closed.
 */
final class EngineShutdownSequence {
    private final IrisEngine engine;
    private boolean runtimeReleased;
    private boolean targetReleased;
    private boolean mantleReleased;
    private boolean engineDataReleased;
    private boolean preservationReleased;

    EngineShutdownSequence(IrisEngine engine) {
        this.engine = engine;
    }

    void close() {
        Throwable failure;
        synchronized (engine.lifecycleLock) {
            if (engine.closed) {
                return;
            }
            engine.lifecycleState = LifecycleState.CLOSING;
            engine.getClosing().set(true);
            engine.backgroundTasks.closeBackgroundTaskAdmission();
            EngineTickRegistry.unregisterTicking(engine);
            engine.getPlatformHooks().shutdownPregenerator(engine);
            try {
                engine.getGenerationSessions().sealAndAwait("close", IrisEngine.SESSION_DRAIN_TIMEOUT_MILLIS, true);
            } catch (GenerationSessionException e) {
                throw new IllegalStateException("Failed to drain Iris generation for close.", e);
            }

            BackgroundTaskDrain backgroundDrain = engine.backgroundTasks.drainBackgroundTasks("close");
            failure = backgroundDrain.failure();
            if (!backgroundDrain.allowsResourceRelease() && failure == null) {
                failure = new IllegalStateException("Iris background tasks remain active during close.");
            }

            if (backgroundDrain.allowsResourceRelease()) {
                Throwable ownershipFailure = runCleanup(null,
                        () -> NativeStructureOwnershipStore.close(engine));
                failure = appendFailure(failure, ownershipFailure);
                if (ownershipFailure == null) {
                    Throwable prefetchFailure = runCleanup(null, engine::savePrefetchOnce);
                    Throwable engineDataFailure = runCleanup(null, engine::saveEngineData);
                    failure = appendFailure(failure, prefetchFailure);
                    failure = appendFailure(failure, engineDataFailure);
                    failure = releaseRuntime(failure);
                    if (runtimeReleased) {
                        failure = releaseTarget(failure);
                    }
                    if (targetReleased) {
                        failure = releaseMantle(failure);
                    }
                    if (prefetchFailure == null
                            && engineDataFailure == null
                            && runtimeReleased
                            && targetReleased
                            && mantleReleased) {
                        failure = releaseEngineDataForShutdown(failure);
                    }
                    if (engineDataReleased) {
                        failure = releasePreservation(failure);
                    }
                }
            }
            if (failure == null
                    && runtimeReleased
                    && targetReleased
                    && mantleReleased
                    && engineDataReleased
                    && preservationReleased) {
                engine.closed = true;
                engine.lifecycleState = LifecycleState.CLOSED;
                IrisLogging.debug("Engine Fully Shutdown!");
            }
        }
        if (failure != null) {
            IrisLogging.error("Iris engine shutdown remains incomplete after cleanup failures for " + engine.getWorld().name() + ".");
            IrisLogging.reportError(failure);
            failure.printStackTrace();
            throw new IllegalStateException("Iris engine shutdown remains incomplete after cleanup failures.", failure);
        }
    }

    void cleanupFailedConstruction(Throwable original) {
        engine.getClosing().set(true);
        engine.backgroundTasks.closeBackgroundTaskAdmission();
        EngineTickRegistry.unregisterTicking(engine);
        engine.lifecycleState = LifecycleState.FAILED;
        Throwable cleanupFailure = null;
        try {
            engine.getGenerationSessions().sealAndAwait("failed initialization", 0L, true);
        } catch (Throwable e) {
            cleanupFailure = appendFailure(cleanupFailure, e);
        }
        engine.backgroundTasks.cancelBackgroundTasks("failed initialization");
        BackgroundTaskDrain backgroundDrain = engine.backgroundTasks.drainBackgroundTasks("failed initialization");
        cleanupFailure = appendFailure(cleanupFailure, backgroundDrain.failure());
        if (!backgroundDrain.allowsResourceRelease()) {
            cleanupFailure = appendFailure(cleanupFailure,
                    new IllegalStateException("Iris background tasks remain active after failed initialization."));
            if (cleanupFailure != original) {
                original.addSuppressed(cleanupFailure);
            }
            return;
        }
        Throwable ownershipFailure = runCleanup(null,
                () -> NativeStructureOwnershipStore.close(engine));
        cleanupFailure = appendFailure(cleanupFailure, ownershipFailure);
        if (ownershipFailure == null) {
            cleanupFailure = closeRuntime(engine.runtime, cleanupFailure);
            engine.runtime = null;
            cleanupFailure = runCleanup(cleanupFailure, engine.getTarget()::close);
            cleanupFailure = runCleanup(cleanupFailure, engine.getMantle()::close);
            cleanupFailure = runCleanup(cleanupFailure, engine.engineDataStore::releaseEngineData);
            engine.closed = true;
            cleanupFailure = runCleanup(cleanupFailure, () -> {
                PreservationRegistry registry = IrisServices.getOrNull(PreservationRegistry.class);
                if (registry != null) {
                    registry.dereference();
                }
            });
        }
        if (cleanupFailure != null && cleanupFailure != original) {
            original.addSuppressed(cleanupFailure);
        }
    }

    Throwable closeAssembly(RuntimeAssembly assembly, Throwable failure) {
        if (assembly == null) {
            return failure;
        }
        failure = runCleanup(failure, () -> {
            if (assembly.worldManager != null) {
                assembly.worldManager.close();
            }
        });
        failure = runCleanup(failure, () -> {
            if (assembly.effects != null) {
                assembly.effects.close();
            }
        });
        failure = runCleanup(failure, () -> {
            if (assembly.mode != null) {
                assembly.mode.close();
            }
        });
        failure = runCleanup(failure, () -> {
            if (assembly.complex != null) {
                assembly.complex.close();
            }
        });
        failure = runCleanup(failure, () -> {
            if (assembly.hash32 != null) {
                assembly.hash32.cancel(true);
            }
        });
        return failure;
    }

    Throwable closeRuntime(EngineRuntime engineRuntime, Throwable failure) {
        if (engineRuntime == null) {
            return failure;
        }
        failure = runCleanup(failure, engineRuntime.worldManager()::close);
        failure = runCleanup(failure, engineRuntime.effects()::close);
        failure = runCleanup(failure, engineRuntime.mode()::close);
        failure = runCleanup(failure, engineRuntime.complex()::close);
        failure = runCleanup(failure, () -> engineRuntime.hash32().cancel(true));
        return failure;
    }

    private Throwable releaseRuntime(Throwable failure) {
        if (runtimeReleased) {
            return failure;
        }
        Throwable runtimeFailure = closeRuntime(engine.runtime, null);
        if (runtimeFailure != null) {
            return appendFailure(failure, runtimeFailure);
        }
        engine.runtime = null;
        runtimeReleased = true;
        return failure;
    }

    private Throwable releaseTarget(Throwable failure) {
        if (targetReleased) {
            return failure;
        }
        Throwable targetFailure = runCleanup(null, engine.getTarget()::close);
        if (targetFailure != null) {
            return appendFailure(failure, targetFailure);
        }
        targetReleased = true;
        return failure;
    }

    private Throwable releaseMantle(Throwable failure) {
        if (mantleReleased) {
            return failure;
        }
        Throwable mantleFailure = runCleanup(null, engine.getMantle()::close);
        if (mantleFailure != null) {
            return appendFailure(failure, mantleFailure);
        }
        mantleReleased = true;
        return failure;
    }

    private Throwable releaseEngineDataForShutdown(Throwable failure) {
        if (engineDataReleased) {
            return failure;
        }
        Throwable dataFailure = runCleanup(null, engine.engineDataStore::releaseEngineData);
        if (dataFailure != null) {
            return appendFailure(failure, dataFailure);
        }
        engineDataReleased = true;
        return failure;
    }

    private Throwable releasePreservation(Throwable failure) {
        if (preservationReleased) {
            return failure;
        }
        Throwable preservationFailure = runCleanup(null, () -> {
            PreservationRegistry registry = IrisServices.getOrNull(PreservationRegistry.class);
            if (registry != null) {
                registry.dereference();
            }
        });
        if (preservationFailure != null) {
            return appendFailure(failure, preservationFailure);
        }
        preservationReleased = true;
        return failure;
    }

    static Throwable runCleanup(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable e) {
            return appendFailure(failure, e);
        }
        return failure;
    }

    static Throwable appendFailure(Throwable failure, Throwable additional) {
        if (additional == null) {
            return failure;
        }
        if (failure == null) {
            return additional;
        }
        if (failure != additional) {
            failure.addSuppressed(additional);
        }
        return failure;
    }

    static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(throwable);
    }
}
