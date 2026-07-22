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

package art.arcane.iris.core.service;

import art.arcane.iris.core.localization.BukkitUiMessages;
import art.arcane.iris.core.localization.IrisLanguage;
import art.arcane.iris.core.localization.RuntimeUiMessages;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.tools.IrisToolbelt;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.platform.PlatformChunkGenerator;
import art.arcane.volmlib.util.board.Board;
import art.arcane.volmlib.util.board.BoardProvider;
import art.arcane.volmlib.util.board.BoardSettings;
import art.arcane.volmlib.util.board.ScoreDirection;
import art.arcane.volmlib.util.format.Form;
import art.arcane.iris.util.common.plugin.IrisService;
import art.arcane.iris.util.common.scheduling.J;
import art.arcane.volmlib.util.matter.MatterCavern;
import art.arcane.volmlib.util.localization.MessageArgument;
import lombok.Data;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoardSVC implements IrisService, BoardProvider {
    private final Map<Player, PlayerBoard> boards = new ConcurrentHashMap<>();
    private final Set<UUID> hiddenPlayers = ConcurrentHashMap.newKeySet();
    private volatile BoardSettings settings;
    private volatile boolean boardEnabled;

    @Override
    public void onEnable() {
        boardEnabled = true;
        settings = BoardSettings.builder()
                .boardProvider(this)
                .scoreDirection(ScoreDirection.DOWN)
                .build();

        cleanupLeakedMainScoreboard();

        for (Player player : art.arcane.iris.platform.bukkit.BukkitPlatform.volmitPlugin().getServer().getOnlinePlayers()) {
            J.runEntity(player, () -> updatePlayer(player));
        }
    }

    private void cleanupLeakedMainScoreboard() {
        try {
            if (Bukkit.getScoreboardManager() == null) {
                return;
            }
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            if (main == null) {
                return;
            }

            Objective objective = main.getObjective("board");
            if (objective == null || !"Iris".equalsIgnoreCase(ChatColor.stripColor(objective.getDisplayName()))) {
                return;
            }

            objective.unregister();
            Team team = main.getTeam("board");
            if (team != null) {
                team.unregister();
            }
        } catch (Throwable e) {
            IrisLogging.reportError(e);
        }
    }

    @Override
    public void onDisable() {
        boardEnabled = false;
        for (PlayerBoard board : new ArrayList<>(boards.values())) {
            board.cancel();
        }
        boards.clear();
        hiddenPlayers.clear();
        settings = null;
    }

    @EventHandler
    public void on(PlayerChangedWorldEvent e) {
        J.runEntity(e.getPlayer(), () -> updatePlayer(e.getPlayer()));
    }

    @EventHandler
    public void on(PlayerJoinEvent e) {
        J.runEntity(e.getPlayer(), () -> updatePlayer(e.getPlayer()));
    }

    @EventHandler
    public void on(PlayerQuitEvent e) {
        remove(e.getPlayer());
        clearPlayerPreference(e.getPlayer().getUniqueId());
    }

    public void updatePlayer(Player p) {
        if (!boardEnabled || settings == null) {
            return;
        }

        if (!J.isOwnedByCurrentRegion(p)) {
            J.runEntity(p, () -> updatePlayer(p));
            return;
        }

        if (isEligibleWorld(p)) {
            boards.computeIfAbsent(p, PlayerBoard::new);
            return;
        }

        remove(p);
    }

    private void remove(Player player) {
        if (player == null) {
            return;
        }

        if (!J.isOwnedByCurrentRegion(player)) {
            J.runEntity(player, () -> remove(player));
            return;
        }

        PlayerBoard board = boards.remove(player);
        if (board != null) {
            board.cancel();
        }
    }

    public boolean toggle(Player player) {
        Objects.requireNonNull(player, "player");
        boolean visible = togglePlayerBoard(player.getUniqueId());
        updatePlayer(player);
        return visible;
    }

    @Override
    public String getTitle(Player player) {
        return IrisLanguage.text(BukkitUiMessages.SCOREBOARD_TITLE);
    }

    @Override
    public List<String> getLines(Player player) {
        PlayerBoard board = boards.get(player);
        if (board == null) {
            return List.of();
        }
        return board.lines;
    }

    private boolean isEligibleWorld(Player player) {
        if (player == null) {
            return false;
        }

        return isPlayerBoardEnabled(player.getUniqueId())
                && isStudioGeneratorEligible(IrisToolbelt.access(player.getWorld()));
    }

    static boolean isStudioGeneratorEligible(PlatformChunkGenerator generator) {
        return generator != null
                && generator.isStudio()
                && !generator.isClosing()
                && generator.getEngine() != null;
    }

    boolean isPlayerBoardEnabled(UUID playerId) {
        return playerId != null && !hiddenPlayers.contains(playerId);
    }

    boolean togglePlayerBoard(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (hiddenPlayers.remove(playerId)) {
            return true;
        }

        hiddenPlayers.add(playerId);
        return false;
    }

    void clearPlayerPreference(UUID playerId) {
        if (playerId != null) {
            hiddenPlayers.remove(playerId);
        }
    }

    static Scoreboard selectScoreboardToRestore(Scoreboard active, Scoreboard iris, Scoreboard previous) {
        return Objects.equals(active, iris) ? previous : active;
    }

    @Data
    public class PlayerBoard {
        private final Player player;
        private final Board board;
        private final Scoreboard previousScoreboard;
        private final Scoreboard irisScoreboard;
        private volatile List<String> lines;
        private volatile boolean cancelled;

        public PlayerBoard(Player player) {
            this.player = player;
            Scoreboard previous = null;
            Scoreboard assigned = null;
            try {
                previous = player.getScoreboard();
                if (Bukkit.getScoreboardManager() != null
                        && Objects.equals(previous, Bukkit.getScoreboardManager().getMainScoreboard())) {
                    player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
                }
                assigned = player.getScoreboard();
            } catch (Throwable e) {
                IrisLogging.reportError("Failed to prepare the Studio scoreboard for " + player.getName() + ".", e);
            }
            this.previousScoreboard = previous;
            this.irisScoreboard = assigned;
            this.board = new Board(player, settings);
            this.lines = new ArrayList<>();
            this.cancelled = false;
            schedule(0);
        }

        private void schedule(int delayTicks) {
            if (cancelled || !boardEnabled || !player.isOnline()) {
                return;
            }
            J.runEntity(player, this::tick, delayTicks);
        }

        private void tick() {
            if (cancelled || !boardEnabled || !player.isOnline()) {
                return;
            }

            if (!isEligibleWorld(player)) {
                boards.remove(player, this);
                cancel();
                return;
            }

            update();
            board.update();
            schedule(20);
        }

        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            if (J.isOwnedByCurrentRegion(player) && player.isOnline()) {
                removeNow();
            } else {
                J.runEntity(player, this::removeNow);
            }
        }

        private void removeNow() {
            Scoreboard activeScoreboard = null;
            try {
                activeScoreboard = player.getScoreboard();
                board.remove();
                if (!player.isOnline()) {
                    return;
                }

                Scoreboard restore = selectScoreboardToRestore(
                        activeScoreboard,
                        irisScoreboard,
                        previousScoreboard);
                if (restore != null && !Objects.equals(player.getScoreboard(), restore)) {
                    player.setScoreboard(restore);
                }
            } catch (Throwable e) {
                IrisLogging.reportError("Failed to remove the Studio scoreboard for " + player.getName() + ".", e);
                if (activeScoreboard != null && player.isOnline()) {
                    player.setScoreboard(activeScoreboard);
                }
            }
        }

        public void update() {
            World world = player.getWorld();
            Location loc = player.getLocation();

            PlatformChunkGenerator access = IrisToolbelt.access(world);
            if (access == null) {
                return;
            }

            Engine engine = access.getEngine();
            if (engine == null) {
                return;
            }

            int x = loc.getBlockX();
            int y = loc.getBlockY() - world.getMinHeight();
            int z = loc.getBlockZ();

            List<String> lines = new ArrayList<>(this.lines.size());
            lines.add("&7&m                   ");
            lines.add(IrisLanguage.text(
                    BukkitUiMessages.SCOREBOARD_SPEED,
                    MessageArgument.trusted("speed", Form.f(engine.getGeneratedPerSecond(), 0)),
                    MessageArgument.trusted("duration", Form.duration(1000D / engine.getGeneratedPerSecond(), 0))
            ));
            lines.add(IrisLanguage.text(BukkitUiMessages.SCOREBOARD_CACHE, MessageArgument.trusted("count", Form.f(IrisData.cacheSize()))));
            lines.add(IrisLanguage.text(BukkitUiMessages.SCOREBOARD_MANTLE, MessageArgument.trusted("count", engine.getMantle().getLoadedRegionCount())));

            if (IrisSettings.get().getGeneral().debug) {
                boolean carving = engine.getMantle().getMantle().get(x, y, z, MatterCavern.class) != null;
                lines.add(IrisLanguage.text(
                        BukkitUiMessages.SCOREBOARD_CARVING,
                        MessageArgument.trusted("state", IrisLanguage.text(carving ? RuntimeUiMessages.STATUS_TRUE : RuntimeUiMessages.STATUS_FALSE))
                ));
            }

            lines.add("&7&m                   ");
            lines.add(IrisLanguage.text(BukkitUiMessages.SCOREBOARD_REGION, MessageArgument.untrusted("region", engine.getRegion(x, z).getName())));
            lines.add(IrisLanguage.text(BukkitUiMessages.SCOREBOARD_BIOME, MessageArgument.untrusted("biome", engine.getBiomeOrMantle(x, y, z).getName())));
            lines.add(IrisLanguage.text(BukkitUiMessages.SCOREBOARD_HEIGHT, MessageArgument.trusted("height", Math.round(engine.getHeight(x, z)))));
            lines.add(IrisLanguage.text(BukkitUiMessages.SCOREBOARD_SLOPE, MessageArgument.trusted("slope", Form.f(engine.getComplex().getSlopeStream().get(x, z), 2))));
            lines.add(IrisLanguage.text(BukkitUiMessages.SCOREBOARD_BLOCK_UPDATES, MessageArgument.trusted("updates", Form.f(engine.getBlockUpdatesPerSecond()))));
            lines.add("&7&m                   ");
            this.lines = lines;
        }
    }
}
