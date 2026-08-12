/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.modded;

import art.arcane.iris.core.pack.PackDownloader;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModdedPackInstallerTest {
    @Test
    public void managedPacksIgnoreTheRequestedBranch() {
        assertTrue(ModdedPackInstaller.acceptsBranch("overworld", null));
        assertTrue(ModdedPackInstaller.acceptsBranch("underworld", "feature/arbitrary"));
    }

    @Test
    public void nonManagedPacksStillRequireAValidBranch() {
        assertTrue(ModdedPackInstaller.acceptsBranch("custom", "stable"));
        assertFalse(ModdedPackInstaller.acceptsBranch("custom", null));
        assertFalse(ModdedPackInstaller.acceptsBranch("custom", "feature/arbitrary"));
    }

    @Test
    public void startupBatchDefersDatapackRefresh() {
        PackDownloader.PackInstallResult changed = new PackDownloader.PackInstallResult("underworld", true, true);

        assertFalse(ModdedPackInstaller.shouldRefreshDatapack(changed, false));
        assertTrue(ModdedPackInstaller.shouldRefreshDatapack(changed, true));
        assertFalse(ModdedPackInstaller.shouldRefreshDatapack(
                new PackDownloader.PackInstallResult("underworld", false, false),
                true
        ));
    }
}
