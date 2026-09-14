package dev.gameheist.paper.npc;

import dev.gameheist.domain.combat.*;
import dev.gameheist.domain.npc.GuardState;
import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.runtime.instance.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import java.util.*;
import java.util.logging.Logger;

/** Server-thread adapter: only scoped actors and server ray traces can receive carbine damage. */
public final class CombatController implements Listener {
    private final InstanceManager instances;
    private final PlayerSessions players;
    private final GuardController guards;
    private final Logger logger;
    private final dev.gameheist.paper.pack.HeistAudio audio;
    public CombatController(InstanceManager instances, PlayerSessions players, GuardController guards, Logger logger,
                            dev.gameheist.paper.pack.HeistAudio audio) {
        this.instances = instances;
        this.players = players;
        this.guards = guards;
        this.logger = logger;
        this.audio = audio;
    }
    public void tick() {
        for (var instance : instances.all()) {
            UUID id = instance.match().id();
            if (instance.state() != InstanceState.RUNNING || instances.combatSnapshot(id).isEmpty()) continue;
            try {
                for (UUID playerId : instance.match().participants().keySet()) {
                    Player player = present(playerId, instance);
                    if (player != null) players.equipCombat(player);
                }
                if (instances.claimWave(id)) {
                    guards.reinforce(id);
                    broadcast(instance, "Responders have arrived at the guard patrol entrances!");
                }
                for (var diagnostic : guards.decisions(id)) {
                    if (!instances.snapshot(id).match().phase().gameplay()) break;
                    var actor = guards.actors(id).get(diagnostic.id());
                    if (actor == null) continue;
                    var state = instances.combatSnapshot(id).orElseThrow();
                    if (state.guards().getOrDefault(diagnostic.id(), 0) == 0) { actor.release(); continue; }
                    Player target = diagnostic.decision().targetId().map(playerId -> present(playerId, instance)).orElse(null);
                    boolean eligible = actor.alive() && diagnostic.decision().state() == GuardState.PURSUIT && target != null;
                    double distance = eligible ? Math.sqrt(dev.gameheist.domain.npc.GuardPerception.distanceSquared(
                            actor.eyePosition(), PaperGuardActor.position(target.getEyeLocation()))) : Double.POSITIVE_INFINITY;
                    var attack = instances.attack(id, diagnostic.id(), Optional.ofNullable(target).map(Player::getUniqueId),
                            distance, eligible && actor.canSee(target.getUniqueId()));
                    if (attack == CombatRun.Attack.AIMING) {
                        target.sendMessage(Component.text(diagnostic.id() + " is aiming at you — take cover!", NamedTextColor.GOLD));
                    } else if (attack == CombatRun.Attack.HIT) {
                        boolean downed = !instances.activePlayer(target.getUniqueId());
                        target.sendMessage(Component.text(downed ? "DOWNED — a teammate must revive you. Carried loot returned to its marker."
                                : "Hit by " + diagnostic.id() + "!", NamedTextColor.RED));
                        if (downed) broadcast(instance, target.getName() + " is down! Sneak + right click them to revive.");
                    }
                }
                if (!instances.snapshot(id).match().phase().gameplay()) continue;
                var state = instances.combatSnapshot(id).orElseThrow();
                for (var entry : state.players().entrySet()) {
                    if (entry.getValue().reviving().isEmpty()) continue;
                    Player helper = present(entry.getKey(), instance);
                    Player target = present(entry.getValue().reviving().orElseThrow(), instance);
                    boolean valid = helper != null && target != null;
                    if (instances.updateRevive(entry.getKey(), valid ? helper.getLocation().distance(target.getLocation()) : Double.POSITIVE_INFINITY,
                            valid && helper.hasLineOfSight(target), valid && helper.isSneaking())) {
                        broadcast(instance, helper.getName() + " revived " + target.getName() + ".");
                    }
                }
            } catch (Exception failure) {
                logger.log(java.util.logging.Level.SEVERE, "Combat failed for " + id, failure);
                instances.stop(id, "combat_system_failure");
            }
        }
    }

    @EventHandler public void onFire(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.HAND && (event.getAction() == Action.LEFT_CLICK_AIR
                || event.getAction() == Action.LEFT_CLICK_BLOCK)) fire(event.getPlayer());
    }
    @EventHandler public void onMedkit(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) || !players.contains(player.getUniqueId())
                || player.getInventory().getHeldItemSlot() != 1
                || player.getInventory().getItemInMainHand().getType() != Material.PAPER) return;
        event.setCancelled(true);
        if (combatInstance(player) == null) return;
        if (instances.useMedkit(player.getUniqueId())) {
            player.getInventory().setItem(1, null);
            player.sendMessage(Component.text("Medkit used: restored up to 40 HP.", NamedTextColor.GREEN));
        } else player.sendActionBar(Component.text("Medkit requires an injury and cannot revive you.", NamedTextColor.YELLOW));
    }
    // Entity clicks have a separate event; the domain fire interval absorbs duplicate input events.
    @EventHandler public void onMelee(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && players.contains(player.getUniqueId())) {
            event.setCancelled(true);
            fire(player);
        }
    }
    @EventHandler public void onReload(PlayerSwapHandItemsEvent event) {
        if (!players.contains(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        var instance = combatInstance(event.getPlayer());
        if (instance != null && instances.reload(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage(Component.text("Reloading — 2 seconds", NamedTextColor.AQUA));
            audio.emit(instance, event.getPlayer().getLocation(), dev.gameheist.paper.pack.HeistAudio.Cue.CARBINE_RELOAD);
        }
    }
    @EventHandler public void onRevive(PlayerInteractEntityEvent event) {
        Player helper = event.getPlayer();
        if (!players.contains(helper.getUniqueId())) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) return;
        var instance = combatInstance(helper);
        if (instance == null || present(target.getUniqueId(), instance) == null) return;
        // Verify the selected player is actually under the server-side eye ray, with no intervening blocks.
        var trace = helper.getWorld().rayTrace(helper.getEyeLocation(), helper.getEyeLocation().getDirection(),
                CombatRun.REVIVE_RANGE, FluidCollisionMode.NEVER, true, 0.1, entity -> entity instanceof Player && !entity.equals(helper));
        if (trace == null || !target.equals(trace.getHitEntity())) return;
        if (helper.getInventory().getHeldItemSlot() == 1 && helper.getInventory().getItemInMainHand().getType() == Material.PAPER
                && instances.activePlayer(target.getUniqueId())) {
            if (instances.useMedkit(helper.getUniqueId(), target.getUniqueId(),
                    helper.getLocation().distance(target.getLocation()), helper.hasLineOfSight(target))) {
                helper.getInventory().setItem(1, null);
                helper.sendMessage(Component.text("Medkit used on " + target.getName() + ".", NamedTextColor.GREEN));
                target.sendMessage(Component.text(helper.getName() + " healed you for up to 40 HP.", NamedTextColor.GREEN));
            } else helper.sendActionBar(Component.text("Medkit unavailable or teammate already at full HP.", NamedTextColor.YELLOW));
            return;
        }
        if (!helper.isSneaking()) return;
        if (instances.beginRevive(helper.getUniqueId(), target.getUniqueId(), helper.getLocation().distance(target.getLocation()), helper.hasLineOfSight(target))) {
            helper.sendMessage(Component.text("Reviving — keep sneaking within three blocks. Damage interrupts.", NamedTextColor.AQUA));
        }
    }
    private void fire(Player player) {
        var instance = combatInstance(player);
        if (instance == null || !instances.activePlayer(player.getUniqueId()) || player.getInventory().getHeldItemSlot() != 0
                || player.getInventory().getItemInMainHand().getType() != Material.IRON_HOE || !instances.canFire(player.getUniqueId())) return;
        var eye = player.getEyeLocation();
        var direction = eye.getDirection();
        var block = player.getWorld().rayTraceBlocks(eye, direction, CombatRun.SHOT_RANGE, FluidCollisionMode.NEVER, true);
        double closest = block == null ? CombatRun.SHOT_RANGE : block.getHitPosition().distance(eye.toVector());
        // Crewmates absorb the ray without taking damage; shots never pass through another player.
        var blocker = player.getWorld().rayTraceEntities(eye, direction, closest, 0,
                entity -> entity instanceof Player && !entity.equals(player));
        if (blocker != null) closest = Math.min(closest, blocker.getHitPosition().distance(eye.toVector()));
        String hit = null;
        for (var entry : guards.actors(instance.match().id()).entrySet()) {
            if (!entry.getValue().alive()) continue;
            var trace = entry.getValue().hitBox().rayTrace(eye.toVector(), direction, closest);
            if (trace == null) continue;
            double distance = trace.getHitPosition().distance(eye.toVector());
            if (distance < closest) { closest = distance; hit = entry.getKey(); }
        }
        if (instances.fire(player.getUniqueId(), Optional.ofNullable(hit), closest, true)) {
            player.sendActionBar(Component.text(hit == null ? "Carbine fired" : "Hit " + hit, NamedTextColor.GRAY));
            audio.emit(instance, player.getLocation(), dev.gameheist.paper.pack.HeistAudio.Cue.CARBINE_FIRE);
            // Remove defeated guards immediately so a second player's shot cannot hit their old body.
            var state = instances.combatSnapshot(instance.match().id()).orElseThrow();
            if (hit != null && state.guards().getOrDefault(hit, 0) == 0) guards.actors(instance.match().id()).get(hit).release();
        }
    }
    private InstanceSnapshot combatInstance(Player player) {
        var id = instances.instanceOf(player.getUniqueId());
        if (id.isEmpty()) return null;
        var instance = instances.snapshot(id.orElseThrow());
        if (instance.state() != InstanceState.RUNNING || present(player.getUniqueId(), instance) == null
                || instances.combatSnapshot(instance.match().id()).isEmpty()) return null;
        return instance;
    }
    private Player present(UUID id, InstanceSnapshot instance) {
        Player player = Bukkit.getPlayer(id);
        return instance.match().participants().containsKey(id) && player != null && player.isOnline() && !player.isDead()
                && player.getGameMode() == GameMode.ADVENTURE && players.contains(id)
                && player.getWorld().getName().equals(instance.worldName()) ? player : null;
    }
    private void broadcast(InstanceSnapshot instance, String message) {
        for (UUID id : instance.match().participants().keySet()) {
            Player player = present(id, instance);
            if (player != null) player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
        }
    }
}
