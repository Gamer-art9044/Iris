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

package art.arcane.iris.core.protocol;

import art.arcane.iris.spi.protocol.IrisProtocol;
import org.junit.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class IrisVisionRequestServiceTest {
    private static final Executor DIRECT = Runnable::run;
    private static final Executor DISABLED = runnable -> {
    };

    @Test
    public void saturationDropsOldestRequestsAndCounts() {
        IrisSessionRegistry registry = new IrisSessionRegistry();
        registerReady(registry, "s1", IrisProtocol.CAPABILITY_VISION);
        EngineResolver resolver = sessionId -> {
            throw new AssertionError("disabled executor must not process requests");
        };
        int maxPending = 4;
        int overflow = 6;
        IrisVisionRequestService service = new IrisVisionRequestService(resolver, registry, DISABLED, maxPending);

        for (int index = 0; index < maxPending + overflow; index++) {
            service.handle("s1", index, 0, 0);
        }

        assertEquals(overflow, service.droppedSaturatedCount());
        assertEquals(maxPending, service.pendingSize());
    }

    @Test
    public void nullEngineDropsAndCounts() {
        IrisSessionRegistry registry = new IrisSessionRegistry();
        CountingTransport transport = registerReady(registry, "s1", IrisProtocol.CAPABILITY_VISION);
        EngineResolver resolver = sessionId -> null;
        IrisVisionRequestService service = new IrisVisionRequestService(resolver, registry, DIRECT, 8);

        service.handle("s1", 0, 0, 0);

        assertEquals(1L, service.droppedNoEngineCount());
        assertEquals(0L, service.tilesEncodedCount());
        assertEquals(0, transport.sent.get());
    }

    @Test
    public void missingReadyOrCapableSessionDropsWithoutResolvingEngine() {
        IrisSessionRegistry registry = new IrisSessionRegistry();
        IrisSession awaiting = new IrisSession("awaiting", new CountingTransport());
        registry.register(awaiting);
        IrisSession noCapability = new IrisSession("nocap", new CountingTransport());
        noCapability.markReady(IrisProtocol.PROTOCOL_VERSION, 0L);
        registry.register(noCapability);
        EngineResolver resolver = sessionId -> {
            throw new AssertionError("engine must not be resolved for an ineligible session");
        };
        IrisVisionRequestService service = new IrisVisionRequestService(resolver, registry, DIRECT, 8);

        service.handle("unregistered", 0, 0, 0);
        service.handle("awaiting", 0, 0, 0);
        service.handle("nocap", 0, 0, 0);

        assertEquals(3L, service.droppedNoSessionCount());
        assertEquals(0L, service.droppedNoEngineCount());
        assertEquals(0L, service.tilesEncodedCount());
    }

    @Test
    public void clearSessionDropsOnlyThatSessionsQueuedRequests() {
        IrisSessionRegistry registry = new IrisSessionRegistry();
        registerReady(registry, "s1", IrisProtocol.CAPABILITY_VISION);
        registerReady(registry, "s10", IrisProtocol.CAPABILITY_VISION);
        EngineResolver resolver = sessionId -> {
            throw new AssertionError("disabled executor must not process requests");
        };
        IrisVisionRequestService service = new IrisVisionRequestService(resolver, registry, DISABLED, 8);

        service.handle("s1", 0, 0, 0);
        service.handle("s1", 1, 0, 0);
        service.handle("s10", 0, 0, 0);
        assertEquals(3, service.pendingSize());

        service.clearSession("s1");

        assertEquals(1, service.pendingSize());
        assertEquals(0L, service.droppedSaturatedCount());
    }

    @Test
    public void clearSessionIgnoresBlankIdsWithoutTouchingTheQueue() {
        IrisSessionRegistry registry = new IrisSessionRegistry();
        registerReady(registry, "s1", IrisProtocol.CAPABILITY_VISION);
        EngineResolver resolver = sessionId -> {
            throw new AssertionError("disabled executor must not process requests");
        };
        IrisVisionRequestService service = new IrisVisionRequestService(resolver, registry, DISABLED, 8);

        service.handle("s1", 0, 0, 0);
        service.clearSession(null);
        service.clearSession("");

        assertEquals(1, service.pendingSize());
    }

    @Test
    public void saturationCountsEveryShedRequestNotJustTheLastBurst() {
        IrisSessionRegistry registry = new IrisSessionRegistry();
        registerReady(registry, "s1", IrisProtocol.CAPABILITY_VISION);
        EngineResolver resolver = sessionId -> {
            throw new AssertionError("disabled executor must not process requests");
        };
        int maxPending = 2;
        IrisVisionRequestService service = new IrisVisionRequestService(resolver, registry, DISABLED, maxPending);

        for (int index = 0; index < 10; index++) {
            service.handle("s1", index, 0, 0);
        }

        assertEquals(8L, service.droppedSaturatedCount());
        assertEquals(maxPending, service.pendingSize());
    }

    private static CountingTransport registerReady(IrisSessionRegistry registry, String sessionId, long capabilities) {
        CountingTransport transport = new CountingTransport();
        IrisSession session = new IrisSession(sessionId, transport);
        session.markReady(IrisProtocol.PROTOCOL_VERSION, capabilities);
        registry.register(session);
        return transport;
    }

    private static final class CountingTransport implements IrisServerTransport {
        private final AtomicInteger sent = new AtomicInteger(0);

        @Override
        public void sendToClient(String sessionId, byte[] frame) {
            sent.incrementAndGet();
        }
    }
}
