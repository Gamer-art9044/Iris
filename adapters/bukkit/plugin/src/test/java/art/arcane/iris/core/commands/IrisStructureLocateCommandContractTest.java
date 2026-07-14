package art.arcane.iris.core.commands;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisStructureLocateCommandContractTest {
    @Test
    public void findReportsDensityLimitAndUsesExactLocatedOrigin() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.commandFindSource")));

        assertTrue(source.contains("LocateStatus.SEARCH_LIMIT_REACHED"));
        assertTrue(source.contains("the density search safety limit was reached"));
        assertTrue(source.contains("result.originX(), result.baseY(), result.originZ()"));
        assertFalse(source.contains("at[0] + 8"));
        assertFalse(source.contains("at[2] + 8"));
    }

    @Test
    public void structureVerifyReportsDensityLimitAndUsesExactLocatedOrigin() throws IOException {
        String source = Files.readString(Path.of(System.getProperty("iris.commandStructureSource")));

        assertTrue(source.contains("[iris-search-limit]"));
        assertTrue(source.contains("density searches safety-limited"));
        assertTrue(source.contains("result.originX() + \",\" + result.baseY() + \",\" + result.originZ()"));
        assertFalse(source.contains("at[0] + 8"));
        assertFalse(source.contains("at[2] + 8"));
    }
}
