package dev.gameheist.paper.listener;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.paper.gameplay.HeistGameplay;
import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.paper.pack.ResourcePackGate;
import dev.gameheist.domain.match.ConnectionStatus;
import dev.gameheist.runtime.arena.ArenaRegistry;
import dev.gameheist.runtime.instance.InstanceManager;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import java.util.logging.Logger;
import java.util.UUID;

/** Protects the graybox; combat damage is applied by the domain, never vanilla events. */
public final class PracticeListener implements Listener {
    private final InstanceManager instances;
    private final ArenaRegistry arenas;
    private final PlayerSessions players;
    private final Logger logger;
    private final HeistGameplay gameplay;
    private final ResourcePackGate packs;
    public PracticeListener(InstanceManager instances, ArenaRegistry arenas, PlayerSessions players, Logger logger,
                            HeistGameplay gameplay, ResourcePackGate packs) {
        this.instances = instances;
        this.arenas = arenas;
        this.players = players;
        this.logger = logger;
        this.gameplay = gameplay;
        this.packs = packs;
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
    @EventHandler public void onReturnBag(PlayerSwapHandItemsEvent event) {
        if (!players.contains(event.getPlayer().getUniqueId()) || !event.getPlayer().isSneaking()) return;
        event.setCancelled(true);
        gameplay.returnBag(event.getPlayer());
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
        // Admission and resource-pack readiness are reconciled on the owner tick.
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        if (!players.contains(player.getUniqueId())) return;
        var id = instances.instanceOf(player.getUniqueId());
        if (id.isEmpty()) return;
        try {
            long generation = instances.disconnect(player.getUniqueId());
            players.suspend(player, generation);
        } catch (RuntimeException failure) {
            logger.warning("Could not suspend departing practice player: " + failure);
            try { players.restore(player); }
            catch (RuntimeException restoreFailure) { logger.warning("Could not restore departing player: " + restoreFailure); }
        }
    }

    public void tick() {
        for (var instance : instances.all()) {
            instance.match().connections().forEach((playerId, state) -> {
                if (instance.match().result().isPresent() || state.status() != ConnectionStatus.VULNERABLE) players.hideAvatar(playerId);
            });
        }
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!players.suspended(id)) continue;
            try {
                var instanceId = instances.instanceOf(id);
                if (instanceId.isEmpty() || !instances.canReconnect(id)) {
                    players.restore(player);
                    player.sendMessage(net.kyori.adventure.text.Component.text("Your reconnect reservation expired."));
                    continue;
                }
                if (!packs.ready(player)) continue;
                long generation = players.suspendedGeneration(id);
                if (!instances.reconnect(id, generation)) continue;
                players.resume(player);
                player.sendMessage(net.kyori.adventure.text.Component.text("Reconnected to the active heist. Your health, ammo and objectives were preserved."));
            } catch (RuntimeException failure) {
                logger.warning("Could not reconcile returning practice player: " + failure);
            }
        }
    }
}
