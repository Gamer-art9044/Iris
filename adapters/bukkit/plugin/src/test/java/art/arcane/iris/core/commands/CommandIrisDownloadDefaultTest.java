package art.arcane.iris.core.commands;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class CommandIrisDownloadDefaultTest {
    @Test
    public void downloadBranchParamDefaultsToTheCoreConstant() throws Exception {
        String source = Files.readString(Path.of("src/main/java/art/arcane/iris/core/commands/CommandIris.java"));
        int branchParam = source.indexOf("name = \"branch\"");
        assertTrue("CommandIris must declare the branch param", branchParam >= 0);
        String declaration = source.substring(branchParam, source.indexOf(')', branchParam));
        assertTrue("branch param default must be PackDownloader.DEFAULT_BRANCH, not a literal",
                declaration.contains("defaultValue = PackDownloader.DEFAULT_BRANCH"));
    }
}
