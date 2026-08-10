package art.arcane.iris.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisSettingsDefaultsTest {
    @Test
    public void datapackIngestStaysAutomaticWhileEditableConversionIsOptIn() {
        IrisSettings.IrisSettingsGeneral settings = new IrisSettings.IrisSettingsGeneral();

        assertTrue(settings.isAutoIngestDatapacks());
        assertFalse(settings.isAutoImportDatapackStructures());
    }
}
