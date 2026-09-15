package dev.gameheist.paper.command;

import dev.gameheist.domain.player.*;
import dev.gameheist.runtime.persistence.StatisticsRepository;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Owner-thread request/reply handling; disconnected requests cannot reach a replacement connection. */
public final class StatisticsView implements Listener {
    private final StatisticsRepository repository;
    private final boolean durable;
    private final Map<UUID, Request> requests = new HashMap<>();

    public StatisticsView(StatisticsRepository repository, boolean durable) {
        this.repository = repository;
        this.durable = durable;
    }
    public void request(Player player, StatisticsScope scope) {
        if (!durable && !scope.practice()) throw new IllegalStateException("Production statistics require durable storage");
        if (requests.containsKey(player.getUniqueId())) throw new IllegalStateException("Wait a few seconds before requesting statistics again");
        if (requests.size() >= 128) throw new IllegalStateException("Statistics requests are busy; try again shortly");
        var future = repository.statistics(player.getUniqueId(), scope).toCompletableFuture();
        requests.put(player.getUniqueId(), new Request(player, scope, future, System.nanoTime()));
        player.sendMessage(Component.text("Loading scoped statistics..."));
    }
    public void tick() {
        var iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            var request = iterator.next();
            long elapsed = System.nanoTime() - request.started;
            if (!request.player.isOnline() || Bukkit.getPlayer(request.player.getUniqueId()) != request.player) {
                iterator.remove();
                continue;
            }
            if (!request.delivered && (request.future.isDone() || elapsed > 15_000_000_000L)) {
                request.delivered = true;
                try {
                    if (!request.future.isDone()) throw new IllegalStateException("Statistics timed out");
                    var stats = request.future.join();
                    String best = stats.bestWinMillis().isPresent()
                            ? dev.gameheist.paper.gameplay.RunTimeFormat.format(stats.bestWinMillis().getAsLong()) : "no timed win in available results";
                    request.player.sendMessage(Component.text((request.scope.practice() ? "Practice statistics: " : "Production statistics: ") + request.scope.arena()
                            + " / " + request.scope.difficulty() + " / crew " + request.scope.crewSize()
                            + (durable ? " (saved results)" : " (recent memory history only)")));
                    request.player.sendMessage(Component.text((durable ? "Best saved winning run: " : "Best retained winning run: ") + best));
                    request.player.sendMessage(Component.text("Runs=" + stats.runs() + "; wins=" + stats.wins()
                            + "; losses=" + stats.losses() + "; aborted=" + stats.aborted()
                            + "; stealth wins=" + stats.stealthWins() + "; crew bags=" + stats.crewSecuredBags()));
                    request.player.sendMessage(Component.text("Recorded playtime=" + stats.gameplayMillis() + " ms across " + stats.timedRuns()
                            + "/" + stats.runs() + " timed runs; recorded objective actions=" + stats.objectiveStats().actions()
                            + "; personally secured bags=" + stats.objectiveStats().securedBags()));
                    request.player.sendMessage(Component.text("Your combat contribution: damage dealt=" + stats.damageDealt()
                            + "; damage taken=" + stats.damageTaken() + "; revives=" + stats.revives()));
                } catch (RuntimeException failure) {
                    request.player.sendMessage(Component.text("Statistics unavailable. Try again shortly; no totals were changed."));
                }
            }
            if (request.delivered && elapsed >= 5_000_000_000L) iterator.remove();
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { requests.remove(event.getPlayer().getUniqueId()); }
    private static final class Request {
        private final Player player;
        private final StatisticsScope scope;
        private final CompletableFuture<PlayerStatistics> future;
        private final long started;
        private boolean delivered;
        private Request(Player player, StatisticsScope scope, CompletableFuture<PlayerStatistics> future, long started) {
            this.player = player;
            this.scope = scope;
            this.future = future;
            this.started = started;
        }
    }
}
