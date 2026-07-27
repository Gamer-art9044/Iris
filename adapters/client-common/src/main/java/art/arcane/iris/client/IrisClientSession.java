package art.arcane.iris.client;

import art.arcane.iris.spi.protocol.IrisMessage;
import art.arcane.iris.spi.protocol.IrisMessageCodec;
import art.arcane.iris.spi.protocol.IrisProtocol;

import java.util.function.LongSupplier;

public final class IrisClientSession {
    private static final long CLIENT_CAPABILITIES = IrisProtocol.CAPABILITY_PREGEN | IrisProtocol.CAPABILITY_VISION | IrisProtocol.CAPABILITY_CURSOR | IrisProtocol.CAPABILITY_STUDIO;
    private static final long HELLO_RETRY_MILLIS = 2_000L;
    private static final int MAX_HELLO_ATTEMPTS = 5;

    private final LongSupplier clock;
    private volatile State state;
    private volatile long serverCapabilities;
    private volatile boolean irisActive;
    private volatile String serverBrand;
    private volatile ClientPacketSink sink;
    private volatile long nextHelloAt;
    private volatile int helloAttempts;

    public IrisClientSession() {
        this(System::currentTimeMillis);
    }

    IrisClientSession(LongSupplier clock) {
        this.clock = clock;
        this.state = State.IDLE;
        this.serverCapabilities = 0L;
        this.irisActive = false;
        this.serverBrand = "";
        this.sink = null;
        this.nextHelloAt = Long.MAX_VALUE;
        this.helloAttempts = 0;
    }

    public void bind(ClientPacketSink boundSink) {
        this.sink = boundSink;
    }

    public State state() {
        return state;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public boolean irisActive() {
        return irisActive;
    }

    public long serverCapabilities() {
        return serverCapabilities;
    }

    public String serverBrand() {
        return serverBrand;
    }

    public void sendHello() {
        helloAttempts = 0;
        state = State.AWAITING_HELLO;
        sendHelloAttempt();
    }

    public void tick() {
        if (state != State.AWAITING_HELLO || clock.getAsLong() < nextHelloAt) {
            return;
        }
        if (helloAttempts >= MAX_HELLO_ATTEMPTS) {
            state = State.UNSUPPORTED;
            return;
        }
        sendHelloAttempt();
    }

    private void sendHelloAttempt() {
        ClientPacketSink activeSink = sink;
        if (activeSink == null) {
            return;
        }
        byte[] frame = IrisMessageCodec.encode(new IrisMessage.ClientHello(IrisProtocol.PROTOCOL_VERSION, CLIENT_CAPABILITIES));
        state = State.AWAITING_HELLO;
        helloAttempts++;
        nextHelloAt = clock.getAsLong() + HELLO_RETRY_MILLIS;
        activeSink.send(frame);
    }

    public void onServerHello(IrisMessage.ServerHello hello) {
        if (hello.protocolVersion() != IrisProtocol.PROTOCOL_VERSION) {
            this.state = State.INCOMPATIBLE;
            return;
        }
        this.serverCapabilities = hello.capabilities();
        this.irisActive = hello.irisActive();
        this.serverBrand = hello.serverBrand();
        this.state = State.READY;
    }

    public void reset() {
        this.state = State.IDLE;
        this.serverCapabilities = 0L;
        this.irisActive = false;
        this.serverBrand = "";
        this.nextHelloAt = Long.MAX_VALUE;
        this.helloAttempts = 0;
    }

    public enum State {
        IDLE,
        AWAITING_HELLO,
        READY,
        UNSUPPORTED,
        INCOMPATIBLE
    }
}
