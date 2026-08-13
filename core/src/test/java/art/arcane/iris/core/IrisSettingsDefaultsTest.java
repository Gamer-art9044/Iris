package art.arcane.iris.core;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class IrisSettingsDefaultsTest {
    @Test
    public void concurrencyIsNotSerializedIntoSettingsJson() {
        String json = new Gson().toJson(new IrisSettings());

        assertFalse("settings.json must not advertise an unconfigurable concurrency block",
                json.contains("\"concurrency\""));
    }

    @Test
    public void legacyConcurrencyBlockStillLoadsAndKeepsDerivedValues() {
        IrisSettings loaded = new Gson().fromJson(
                "{\"concurrency\":{\"parallelism\":8,\"ioParallelism\":4}}", IrisSettings.class);

        assertNotNull(loaded.getConcurrency());
        assertEquals(Math.max(2, Runtime.getRuntime().availableProcessors()),
                loaded.getConcurrency().getParallelism());
    }

    @Test
    public void datapackIngestStaysAutomaticWhileEditableConversionIsOptIn() {
        IrisSettings.IrisSettingsGeneral settings = new IrisSettings.IrisSettingsGeneral();

        assertTrue(settings.isAutoIngestDatapacks());
        assertFalse(settings.isAutoImportDatapackStructures());
    }
}
