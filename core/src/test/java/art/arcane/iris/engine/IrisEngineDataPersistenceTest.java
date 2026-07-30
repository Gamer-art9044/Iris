package art.arcane.iris.engine;

import art.arcane.iris.engine.object.IrisEngineData;
import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class IrisEngineDataPersistenceTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void atomicWriteCreatesAndReplacesEngineData() throws Exception {
        File folder = temporaryFolder.newFolder("engine-data");
        File output = new File(folder, "dimension.json");
        IrisEngineData first = new IrisEngineData();
        first.getStatistics().setVersion(10);

        EngineDataStore.writeEngineDataAtomically(output, first);

        IrisEngineData firstRead = new Gson().fromJson(Files.readString(output.toPath()), IrisEngineData.class);
        assertEquals(10, firstRead.getStatistics().getVersion());

        IrisEngineData replacement = new IrisEngineData();
        replacement.getStatistics().setVersion(20);
        EngineDataStore.writeEngineDataAtomically(output, replacement);

        IrisEngineData replacementRead = new Gson().fromJson(Files.readString(output.toPath()), IrisEngineData.class);
        assertEquals(20, replacementRead.getStatistics().getVersion());
        File[] temporaryFiles = folder.listFiles((ignored, name) -> name.endsWith(".tmp"));
        assertFalse(temporaryFiles != null && temporaryFiles.length > 0);
    }

    @Test(expected = IOException.class)
    public void atomicWriteRejectsParentlessPath() throws Exception {
        EngineDataStore.writeEngineDataAtomically(new File("parentless-engine-data.json"), new IrisEngineData());
    }
}
