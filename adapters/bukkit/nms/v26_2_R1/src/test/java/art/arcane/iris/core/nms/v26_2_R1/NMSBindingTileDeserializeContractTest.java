package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class NMSBindingTileDeserializeContractTest {
    @Test
    public void ownedRegionTileDataMergesBeforeScheduledFallback() throws IOException {
        Path chunkGeneratorSource = Path.of(System.getProperty("iris.nmsChunkGeneratorSource"));
        String source = Files.readString(chunkGeneratorSource.resolveSibling("NMSBinding.java")).replace("\r\n", "\n");
        int methodStart = source.indexOf("public void deserializeTile(");
        int methodEnd = source.indexOf("\n    private void merge(", methodStart);
        String method = source.substring(methodStart, methodEnd);

        int ownershipCheck = method.indexOf("J.isOwnedByCurrentRegion(");
        int synchronousMerge = method.indexOf("merge(level, blockPos, tag);", ownershipCheck);
        int synchronousReturn = method.indexOf("return;", synchronousMerge);
        int scheduledFallback = method.indexOf("J.runAt(pos, () -> merge(level, blockPos, tag))");

        assertTrue(ownershipCheck >= 0);
        assertTrue(synchronousMerge > ownershipCheck);
        assertTrue(synchronousReturn > synchronousMerge);
        assertTrue(scheduledFallback > synchronousReturn);
    }
}
