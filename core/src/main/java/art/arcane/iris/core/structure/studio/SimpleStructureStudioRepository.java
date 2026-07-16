/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
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

package art.arcane.iris.core.structure.studio;

import art.arcane.iris.core.structure.authoring.StructureKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public final class SimpleStructureStudioRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ConcurrentMap<Path, ReentrantLock> ROOT_LOCKS = new ConcurrentHashMap<>();

    private final Path packRoot;
    private final Path draftsRoot;
    private final ReentrantLock rootLock;

    public SimpleStructureStudioRepository(Path packRoot) {
        this.packRoot = Objects.requireNonNull(packRoot, "packRoot").toAbsolutePath().normalize();
        draftsRoot = this.packRoot.resolve(".iris/structure-studio").normalize();
        rootLock = ROOT_LOCKS.computeIfAbsent(this.packRoot, ignored -> new ReentrantLock());
    }

    public Path packRoot() {
        return packRoot;
    }

    public Path draftPath(StructureKey key) {
        StructureKey activeKey = Objects.requireNonNull(key, "key");
        Path path = draftsRoot.resolve(activeKey.namespace()).resolve(activeKey.path() + ".json").normalize();
        if (!path.startsWith(draftsRoot)) {
            throw new IllegalArgumentException("Studio draft key escapes the pack: " + key);
        }
        return path;
    }

    public Optional<SimpleStructureStudioDraft> load(StructureKey key) throws IOException {
        Path target = draftPath(key);
        rootLock.lock();
        try {
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            try {
                SimpleStructureStudioDraft draft = GSON.fromJson(
                        Files.readString(target, StandardCharsets.UTF_8), SimpleStructureStudioDraft.class);
                if (draft == null) {
                    throw new IOException("Studio draft is empty: " + target);
                }
                return Optional.of(draft);
            } catch (RuntimeException e) {
                throw new IOException("Invalid Studio draft " + target + ": " + e.getMessage(), e);
            }
        } finally {
            rootLock.unlock();
        }
    }

    public void save(StructureKey key, SimpleStructureStudioDraft draft) throws IOException {
        Path target = draftPath(key);
        byte[] content = GSON.toJson(Objects.requireNonNull(draft, "draft")).getBytes(StandardCharsets.UTF_8);
        rootLock.lock();
        Path staged = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(staged, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveReplace(staged, target);
        } finally {
            try {
                Files.deleteIfExists(staged);
            } finally {
                rootLock.unlock();
            }
        }
    }

    private void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
