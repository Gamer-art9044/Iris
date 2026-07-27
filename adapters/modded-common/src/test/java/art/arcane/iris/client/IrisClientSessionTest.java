package art.arcane.iris.client;

import art.arcane.iris.spi.protocol.IrisMessage;
import art.arcane.iris.spi.protocol.IrisProtocol;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IrisClientSessionTest {
    @Test
    public void retriesHelloAndEventuallyMarksUnsupported() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger frames = new AtomicInteger();
        IrisClientSession session = new IrisClientSession(clock::get);
        session.bind(frame -> frames.incrementAndGet());

        session.sendHello();
        assertEquals(1, frames.get());
        assertEquals(IrisClientSession.State.AWAITING_HELLO, session.state());

        for (int attempt = 0; attempt < 5; attempt++) {
            clock.addAndGet(2_000L);
            session.tick();
        }

        assertEquals(5, frames.get());
        assertEquals(IrisClientSession.State.UNSUPPORTED, session.state());
    }

    @Test
    public void matchingHelloCompletesRetryingSession() {
        AtomicLong clock = new AtomicLong();
        IrisClientSession session = new IrisClientSession(clock::get);
        session.bind(frame -> {
        });
        session.sendHello();

        session.onServerHello(new IrisMessage.ServerHello(
                IrisProtocol.PROTOCOL_VERSION, IrisProtocol.CAPABILITY_VISION, "Iris", true));

        assertTrue(session.isReady());
        assertEquals("Iris", session.serverBrand());
    }

    @Test
    public void incompatibleHelloIsRejected() {
        IrisClientSession session = new IrisClientSession();
        session.onServerHello(new IrisMessage.ServerHello(
                IrisProtocol.PROTOCOL_VERSION + 1, 0L, "Iris", true));

        assertEquals(IrisClientSession.State.INCOMPATIBLE, session.state());
    }
}
