package dev.gameheist.paper.listener;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.paper.gameplay.HeistGameplay;
import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.runtime.arena.ArenaRegistry;
import dev.gameheist.runtime.instance.InstanceManager;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import java.util.logging.Logger;

/** Protects the graybox; combat damage is applied by the domain, never vanilla events. */
public final class PracticeListener implements Listener {
    private final InstanceManager instances;
    private final ArenaRegistry arenas;
    private final PlayerSessions players;
    private final Logger logger;
    private final HeistGameplay gameplay;
    public PracticeListener(InstanceManager instances, ArenaRegistry arenas, PlayerSessions players, Logger logger,
                            HeistGameplay gameplay) {
        this.instances = instances;
        this.arenas = arenas;
        this.players = players;
        this.logger = logger;
        this.gameplay = gameplay;
    }
    @EventHandler(ignoreCancelled = true) public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && players.contains(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void onHunger(FoodLevelChangeEvent event) {
        if (players.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent event) {
        if (players.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent event) {
        if (players.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void onDrop(PlayerDropItemEvent event) {
        if (players.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (players.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (players.contains(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler public void onPickup(EntityPickupItemEvent event) {
        if (players.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (!players.contains(event.getPlayer().getUniqueId())) return;
        // Custom markers have no vanilla use action; keep vanilla denied but handle our own scoped interaction.
        event.setCancelled(true);
        gameplay.interact(event);
    }
    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent event) {
        if (!players.contains(event.getPlayer().getUniqueId())) return;
        var id = instances.instanceOf(event.getPlayer().getUniqueId());
        if (id.isEmpty() || event.getTo() == null || !event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
        if (!instances.activePlayer(event.getPlayer().getUniqueId())) {
            var fixed = event.getFrom().clone();
            fixed.setYaw(event.getTo().getYaw());
            fixed.setPitch(event.getTo().getPitch());
            event.setTo(fixed);
            return;
        }
        var destination = event.getTo();
        var arena = arenas.require(instances.snapshot(id.orElseThrow()).match().arena());
        if (!arena.bounds().contains(new Position(destination.getX(), destination.getY(), destination.getZ(),
                destination.getYaw(), destination.getPitch()))) event.setTo(event.getFrom());
    }
    @EventHandler(ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent event) {
        var playerId = event.getPlayer().getUniqueId();
        if (players.transferring(playerId) || event.getTo() == null) return;
        if (players.contains(playerId)) {
            if (!instances.activePlayer(playerId)) { event.setCancelled(true); return; }
            var id = instances.instanceOf(playerId);
            if (id.isEmpty()) { event.setCancelled(true); return; }
            var snapshot = instances.snapshot(id.orElseThrow());
            var destination = event.getTo();
            var arena = arenas.require(snapshot.match().arena());
            if (!destination.getWorld().getName().equals(snapshot.worldName())
                    || !arena.bounds().contains(new Position(destination.getX(), destination.getY(), destination.getZ(),
                    destination.getYaw(), destination.getPitch()))) event.setCancelled(true);
        } else if (event.getTo().getWorld().getName().startsWith("heist_")) {
            event.setCancelled(true);
        }
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!players.contains(event.getPlayer().getUniqueId())) return;
        try { players.restore(event.getPlayer()); }
        catch (RuntimeException failure) { logger.warning("Could not restore returning practice player: " + failure); }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        if (!players.contains(player.getUniqueId())) return;
        var id = instances.instanceOf(player.getUniqueId());
        if (id.isEmpty()) return;
        // Abort until reconnect admission is implemented; never pretend the GDD's 90-second flow exists.
        try { players.restore(player); }
        catch (RuntimeException failure) { logger.warning("Could not restore departing player: " + failure); }
        instances.stop(id.orElseThrow(), "practice_player_disconnected");
    }
}
