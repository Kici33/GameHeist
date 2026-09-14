package dev.gameheist.paper.world;

import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Only tracks the state this practice adapter changes. Inventory/health are never replaced. */
public final class PlayerSessions {
    private final World fallback;
    private final Map<UUID, SavedState> saved = new HashMap<>();
    private final Set<UUID> transferring = new HashSet<>();
    public PlayerSessions(World fallback) { this.fallback = Objects.requireNonNull(fallback); }

    public void enter(Player player, Location destination) {
        if (saved.containsKey(player.getUniqueId())) throw new IllegalStateException("Player already has a saved session");
        SavedState state = new SavedState(player.getLocation().clone(), player.getGameMode(), player.getWalkSpeed());
        saved.put(player.getUniqueId(), state);
        if (!transfer(player, destination)) {
            saved.remove(player.getUniqueId());
            throw new IllegalStateException("Teleport was cancelled");
        }
        player.setGameMode(GameMode.ADVENTURE);
    }

    public void restore(Player player) {
        SavedState state = saved.get(player.getUniqueId());
        Location destination = state == null ? fallback.getSpawnLocation() : state.location();
        if (destination.getWorld() == null || Bukkit.getWorld(destination.getWorld().getUID()) == null) {
            destination = fallback.getSpawnLocation();
        }
        if (!transfer(player, destination)) throw new IllegalStateException("Return teleport was cancelled");
        if (state != null) {
            player.setGameMode(state.gameMode());
            player.setWalkSpeed(state.walkSpeed());
        }
        saved.remove(player.getUniqueId());
    }
    public boolean contains(UUID playerId) { return saved.containsKey(playerId); }
    public void carrying(Player player, boolean carrying) {
        SavedState state = saved.get(player.getUniqueId());
        if (state == null) return;
        float speed = carrying ? state.walkSpeed() * 0.8f : state.walkSpeed();
        if (player.getWalkSpeed() != speed) player.setWalkSpeed(speed);
    }
    public boolean transferring(UUID playerId) { return transferring.contains(playerId); }
    private boolean transfer(Player player, Location destination) {
        transferring.add(player.getUniqueId());
        try { return player.teleport(destination); }
        finally { transferring.remove(player.getUniqueId()); }
    }
    private record SavedState(Location location, GameMode gameMode, float walkSpeed) {}
}
