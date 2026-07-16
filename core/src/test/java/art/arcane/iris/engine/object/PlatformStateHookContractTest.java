package art.arcane.iris.engine.object;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PlatformStateHookContractTest {
    @Test
    public void missingPlatformRotatorFailsLoudly() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IrisObjectRotation.requirePlatformRotator(null));

        assertEquals("No platform block-state rotator is bound", error.getMessage());
    }

    @Test
    public void missingPlatformMergerFailsLoudly() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BlockDataMergeSupport.requirePlatformMerger(null));

        assertEquals("No platform block-state merger is bound", error.getMessage());
    }

    @Test
    public void missingPlatformTileFactoryFailsLoudly() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TileData.requirePlatformFactory(null));

        assertEquals("No platform tile-data factory is bound", error.getMessage());
    }

    @Test
    public void missingPlatformTileReaderFailsLoudly() {
        IOException error = assertThrows(IOException.class,
                () -> TileData.requirePlatformReader(null));

        assertEquals("No platform tile-data reader is bound", error.getMessage());
    }
}
