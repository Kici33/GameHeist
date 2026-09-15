package dev.gameheist.paper.command;

import dev.gameheist.domain.player.Progression;
import dev.gameheist.mongo.MongoRewards;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Poll on the owner thread; durable recovery can continue safely after a player leaves. */
public final class ProgressionView implements Listener {
    private final MongoRewards rewards;
    private final Logger logger;
    private final Map<UUID, Request> requests = new HashMap<>();
    private CompletableFuture<Integer> recovery;
    private long nextRecovery;
    private long nextWarning;
    public ProgressionView(MongoRewards rewards, Logger logger) { this.rewards = rewards; this.logger = logger; }
    public void request(Player player) {
        if (rewards == null) { player.sendMessage(Component.text("Progression requires durable storage. Practice grants no rewards.")); return; }
        if (requests.containsKey(player.getUniqueId()) || requests.size() >= 128) throw new IllegalStateException("Progression busy; try again shortly");
        requests.put(player.getUniqueId(), new Request(player, rewards.progression(player.getUniqueId()).toCompletableFuture(), System.nanoTime()));
        player.sendMessage(Component.text("Loading saved progression and pending rewards…"));
    }
    public void tick() {
        long now = System.nanoTime();
        if (recovery != null && recovery.isDone()) {
            try { recovery.join(); }
            catch (RuntimeException failure) {
                if (now >= nextWarning) {
                    logger.warning("Reward recovery unavailable; durable pending work will be retried. Check MongoDB replica-set health.");
                    nextWarning = now + 60_000_000_000L;
                }
            }
            recovery = null; nextRecovery = now + 5_000_000_000L;
        }
        if (rewards != null && recovery == null && now >= nextRecovery) recovery = rewards.recover(16).toCompletableFuture();
        var iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            var request = iterator.next();
            if (!request.player.isOnline() || Bukkit.getPlayer(request.player.getUniqueId()) != request.player
                    || !request.player.hasPermission("heist.play")) { iterator.remove(); continue; }
            if (!request.future.isDone() && now - request.started < 15_000_000_000L) continue;
            iterator.remove();
            try {
                if (!request.future.isDone()) throw new IllegalStateException("Timed out");
                var value = request.future.join();
                request.player.sendMessage(Component.text("Saved XP=" + value.experience() + "; wins=" + value.wins()
                        + "; unlocked cosmetics=" + value.cosmetics() + "; pending rewards=" + value.pendingRewards()
                        + ". Practice grants no rewards."));
            } catch (RuntimeException failure) { request.player.sendMessage(Component.text("Progression unavailable. Pending rewards remain queued; retry later.")); }
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { requests.remove(event.getPlayer().getUniqueId()); }
    private record Request(Player player, CompletableFuture<Progression> future, long started) { }
}
