package art.arcane.iris.core.pack;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PackDownloadBranchDefaultTest {
    @Test
    public void sharedDefaultBranchIsStable() {
        assertEquals("stable", PackDownloader.DEFAULT_BRANCH);
    }

    @Test
    public void packRepositoryDefaultsToTheSharedBranch() {
        assertEquals(PackDownloader.DEFAULT_BRANCH, IrisPackRepository.builder().build().getBranch());
        assertEquals(PackDownloader.DEFAULT_BRANCH, IrisPackRepository.from("overworld").getBranch());
    }

    @Test
    public void managedPacksIgnoreTheBranchEntirely() {
        assertTrue(PackDownloader.isManagedPack("overworld"));
        assertTrue(PackDownloader.isManagedPack("underworld"));
    }
}
