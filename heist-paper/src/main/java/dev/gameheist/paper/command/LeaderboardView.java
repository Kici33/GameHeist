package dev.gameheist.paper.command;

import dev.gameheist.domain.player.*;
import dev.gameheist.runtime.persistence.LeaderboardRepository;
import dev.gameheist.paper.gameplay.RunTimeFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Owner-thread replies are bound to one player connection; storage runs off-thread. */
public final class LeaderboardView implements Listener {
    private final LeaderboardRepository repository;
    private final Map<UUID, Request> requests = new HashMap<>();
    public LeaderboardView(LeaderboardRepository repository) { this.repository = repository; }
    public void request(Player player, StatisticsScope scope) {
        if (requests.containsKey(player.getUniqueId()) || requests.size() >= 128) throw new IllegalStateException("Leaderboard busy; try again shortly");
        requests.put(player.getUniqueId(), new Request(player, scope, repository.leaderboard(scope).toCompletableFuture(), System.nanoTime()));
        player.sendMessage(Component.text("Loading production leaderboard…"));
    }
    public void tick() {
        var iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            var request = iterator.next();
            if (!request.player.isOnline() || Bukkit.getPlayer(request.player.getUniqueId()) != request.player
                    || !request.player.hasPermission("heist.play")) { iterator.remove(); continue; }
            long elapsed = System.nanoTime() - request.started;
            if (request.delivered) { if (elapsed >= 5_000_000_000L) iterator.remove(); continue; }
            if (!request.future.isDone() && elapsed < 15_000_000_000L) continue;
            request.delivered = true;
            try {
                if (!request.future.isDone()) throw new IllegalStateException("Timed out");
                var rows = request.future.join();
                request.player.sendMessage(Component.text("Production top 10 · " + request.scope.arena() + " · "
                        + request.scope.difficulty() + " · crew " + request.scope.crewSize() + " (refresh within 10s)"));
                if (rows.isEmpty()) request.player.sendMessage(Component.text("No eligible timed production wins yet."));
                int rank = 1;
                for (var row : rows) {
                    var online = Bukkit.getPlayer(row.playerId());
                    String name = online == null ? row.playerId().toString() : online.getName();
                    request.player.sendMessage(Component.text(rank++ + ". " + name + " · " + RunTimeFormat.format(row.bestWinMillis())
                            + " · wins=" + row.wins()));
                }
            } catch (RuntimeException failure) { request.player.sendMessage(Component.text("Leaderboard unavailable; durable production storage is required. Try again later.")); }
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { requests.remove(event.getPlayer().getUniqueId()); }
    private static final class Request {
        private final Player player;
        private final StatisticsScope scope;
        private final CompletableFuture<List<LeaderboardEntry>> future;
        private final long started;
        private boolean delivered;
        private Request(Player player, StatisticsScope scope, CompletableFuture<List<LeaderboardEntry>> future, long started) {
            this.player = player; this.scope = scope; this.future = future; this.started = started;
        }
    }
}
