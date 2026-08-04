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

package art.arcane.iris.core.pack;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PackDownloaderTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    @Test
    public void resolvesDefaultOverworldBetaRelease() {
        assertEquals(
                "https://github.com/IrisDimensions/overworld/releases/download/beta/overworld.zip",
                PackDownloader.defaultOverworldReleaseUrl()
        );
        assertTrue(PackDownloader.isDefaultOverworld("overworld"));
    }

    @Test
    public void rejectsOtherPacksAsDefaultOverworld() {
        assertFalse(PackDownloader.isDefaultOverworld("theend"));
        assertFalse(PackDownloader.isDefaultOverworld(""));
        assertFalse(PackDownloader.isDefaultOverworld(null));
    }

    @Test
    public void resolvesBranchReference() {
        assertEquals(
                "https://codeload.github.com/IrisDimensions/overworld/zip/refs/heads/feature/release",
                PackDownloader.resolveGithubArchiveUrl("IrisDimensions/overworld", "feature/release")
        );
    }

    @Test
    public void resolvesQualifiedHeadReference() {
        assertEquals(
                "https://codeload.github.com/IrisDimensions/overworld/zip/refs/heads/master",
                PackDownloader.resolveGithubArchiveUrl("IrisDimensions/overworld", "refs/heads/master")
        );
    }

    @Test
    public void resolvesTagReference() {
        assertEquals(
                "https://codeload.github.com/IrisDimensions/overworld/zip/refs/tags/v4.0.0",
                PackDownloader.resolveGithubArchiveUrl("IrisDimensions/overworld", "refs/tags/v4.0.0")
        );
    }

    @Test
    public void resolvesCommitReference() {
        assertEquals(
                "https://github.com/IrisDimensions/overworld/archive/8e32852ee6ecd039fae27a36f701f57cdc02e83f.zip",
                PackDownloader.resolveGithubArchiveUrl("IrisDimensions/overworld", "8e32852ee6ecd039fae27a36f701f57cdc02e83f")
        );
    }

    @Test
    public void isPackPresentRequiresNonEmptyFolder() throws IOException {
        File packsFolder = temp.newFolder("packs");

        assertFalse(PackDownloader.isPackPresent(packsFolder, "overworld"));
        assertFalse(PackDownloader.isPackPresent(packsFolder, null));
        assertFalse(PackDownloader.isPackPresent(packsFolder, ""));
        assertFalse(PackDownloader.isPackPresent(null, "overworld"));

        File pack = new File(packsFolder, "overworld");
        assertTrue(pack.mkdirs());
        assertFalse(PackDownloader.isPackPresent(packsFolder, "overworld"));

        // A partial import (content but no dimension file) counts as absent so it can be replaced.
        File biomes = new File(pack, "biomes");
        assertTrue(biomes.mkdirs());
        Files.writeString(new File(biomes, "plains.json").toPath(), "{}");
        assertFalse(PackDownloader.isPackPresent(packsFolder, "overworld"));

        File dimensions = new File(pack, "dimensions");
        assertTrue(dimensions.mkdirs());
        Files.writeString(new File(dimensions, "overworld.json").toPath(), "{}");
        assertTrue(PackDownloader.isPackPresent(packsFolder, "overworld"));
    }

    @Test
    public void downloadSkipsWhenExpectedPackAlreadyPresent() throws IOException {
        File packsFolder = temp.newFolder("packs");
        File dimensions = new File(packsFolder, "overworld/dimensions");
        assertTrue(dimensions.mkdirs());
        Files.writeString(new File(dimensions, "overworld.json").toPath(), "{}");

        List<String> feedback = new ArrayList<>();
        // The URL is unreachable on purpose: reaching the network would fail the download and
        // return null, so a non-null key proves the presence check ran before any fetch.
        String key = PackDownloader.download(
                packsFolder,
                "IrisDimensions/overworld",
                "http://127.0.0.1:9/unreachable.zip",
                false,
                true,
                "overworld",
                feedback::add
        );

        assertEquals("overworld", key);
        assertFalse(feedback.isEmpty());
    }

    @Test
    public void rejectsUnsafeRepositoryAndReference() {
        assertThrows(IllegalArgumentException.class, () -> PackDownloader.resolveGithubArchiveUrl("IrisDimensions/overworld?raw=1", "master"));
        assertThrows(IllegalArgumentException.class, () -> PackDownloader.resolveGithubArchiveUrl("../overworld", "master"));
        assertThrows(IllegalArgumentException.class, () -> PackDownloader.resolveGithubArchiveUrl("IrisDimensions/overworld", "refs/heads/../master"));
        assertThrows(IllegalArgumentException.class, () -> PackDownloader.resolveGithubArchiveUrl("IrisDimensions/overworld", "refs/pull/123/head"));
        assertThrows(IllegalArgumentException.class, () -> PackDownloader.resolveGithubArchiveUrl("IrisDimensions/overworld", ""));
    }
}
