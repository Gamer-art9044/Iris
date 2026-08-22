package art.arcane.iris.engine;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class EngineShutdownOwnershipContractTest {
    @Test
    public void ownershipCloseMustSucceedBeforeMantleRelease() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/engine/EngineShutdownSequence.java")).replace("\r\n", "\n");
        int ownershipClose = source.indexOf("NativeStructureOwnershipStore.close(engine)");
        int ownershipGate = source.indexOf("if (ownershipFailure == null)", ownershipClose);
        int mantleRelease = source.indexOf("releaseMantle(failure)", ownershipGate);

        assertTrue(ownershipClose >= 0);
        assertTrue(ownershipGate > ownershipClose);
        assertTrue(mantleRelease > ownershipGate);
    }

    @Test
    public void failedConstructionKeepsMantleOpenWhileOwnershipWritesRemain() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/engine/EngineShutdownSequence.java")).replace("\r\n", "\n");
        int cleanupStart = source.indexOf("void cleanupFailedConstruction");
        int cleanupEnd = source.indexOf("Throwable closeAssembly", cleanupStart);
        String cleanup = source.substring(cleanupStart, cleanupEnd);
        int ownershipClose = cleanup.indexOf("NativeStructureOwnershipStore.close(engine)");
        int ownershipGate = cleanup.indexOf("if (ownershipFailure == null)", ownershipClose);
        int mantleRelease = cleanup.indexOf("engine.getMantle()::close", ownershipGate);
        int closedPublication = cleanup.indexOf("engine.closed = true", ownershipGate);

        assertTrue(ownershipClose >= 0);
        assertTrue(ownershipGate > ownershipClose);
        assertTrue(mantleRelease > ownershipGate);
        assertTrue(closedPublication > ownershipGate);
    }
}
