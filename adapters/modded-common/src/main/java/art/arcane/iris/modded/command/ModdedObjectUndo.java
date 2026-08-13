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

package art.arcane.iris.modded.command;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModdedObjectUndo {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");
    private static final int MAX_ENTRIES_PER_OWNER = 32;
    private static final ConcurrentHashMap<UUID, Deque<Entry>> UNDOS = new ConcurrentHashMap<>();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    public static final UUID CONSOLE = new UUID(0L, 0L);

    private ModdedObjectUndo() {
    }

    private record Entry(ServerLevel level, Map<BlockPos, BlockState> blocks) {
    }

    public static void init() {
        if (INITIALIZED.compareAndSet(false, true)) {
            LOGGER.info("Iris object undo service ready (bounded to {} paste(s) per player)", MAX_ENTRIES_PER_OWNER);
        }
    }

    public static void record(UUID owner, ServerLevel level, Map<BlockPos, BlockState> oldBlocks) {
        if (oldBlocks == null || oldBlocks.isEmpty()) {
            return;
        }
        Deque<Entry> queue = UNDOS.computeIfAbsent(owner, (UUID key) -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(new Entry(level, oldBlocks));
            while (queue.size() > MAX_ENTRIES_PER_OWNER) {
                queue.pollFirst();
            }
        }
    }

    public static int size(UUID owner) {
        Deque<Entry> queue = UNDOS.get(owner);
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            return queue.size();
        }
    }

    public static int undo(UUID owner, int amount) {
        Deque<Entry> queue = UNDOS.get(owner);
        if (queue == null) {
            return 0;
        }
        int reverted = 0;
        while (reverted < amount) {
            Entry entry;
            synchronized (queue) {
                entry = queue.pollLast();
            }
            if (entry == null) {
                break;
            }
            // Identity check, not just null: a studio closed and reopened under the same
            // dimension id must never have blocks replayed into the dead ServerLevel.
            MinecraftServer server = entry.level().getServer();
            if (server == null || server.getLevel(entry.level().dimension()) != entry.level()) {
                LOGGER.warn("Iris object undo: skipped a stale entry for removed dimension {}",
                        entry.level().dimension().identifier());
                continue;
            }
            int writes = 0;
            for (Map.Entry<BlockPos, BlockState> block : entry.blocks().entrySet()) {
                try {
                    entry.level().setBlock(block.getKey(), block.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    writes++;
                } catch (Throwable e) {
                    LOGGER.error("Iris object undo: failed to revert a block at {}", block.getKey(), e);
                }
            }
            LOGGER.info("Iris object undo: reverted {} block(s) in {}", writes, entry.level().dimension().identifier());
            reverted++;
        }
        return reverted;
    }

    /**
     * Drops every entry recorded against the given level. Called on dimension removal so a
     * closed studio releases its block snapshots and the ServerLevel reference.
     */
    public static void forget(ServerLevel level) {
        if (level == null) {
            return;
        }
        UNDOS.entrySet().removeIf((Map.Entry<UUID, Deque<Entry>> ownerEntry) -> {
            Deque<Entry> queue = ownerEntry.getValue();
            synchronized (queue) {
                queue.removeIf((Entry entry) -> entry.level() == level);
                return queue.isEmpty();
            }
        });
    }

    public static void clearAll() {
        UNDOS.clear();
    }
}
