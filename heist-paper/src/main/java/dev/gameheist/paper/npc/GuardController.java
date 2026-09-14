package dev.gameheist.paper.npc;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.match.AlarmState;
import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.runtime.arena.ArenaRegistry;
import dev.gameheist.runtime.instance.*;
import dev.gameheist.runtime.npc.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import java.util.*;
import java.util.logging.Logger;

/** Paper owns entity access; GuardSquad owns bounded scheduling and all decision rules. */
public final class GuardController implements Listener {
    private final InstanceManager instances;
    private final ArenaRegistry arenas;
    private final PlayerSessions players;
    private final Logger logger;
    private final Map<UUID, GuardSquad> squads = new HashMap<>();
    private final Set<UUID> ownedEntities = new HashSet<>();
    private final Map<UUID, Map<String, PaperGuardActor>> actors = new HashMap<>();

    public GuardController(InstanceManager instances, ArenaRegistry arenas, PlayerSessions players, Logger logger) {
        this.instances = instances;
        this.arenas = arenas;
        this.players = players;
        this.logger = logger;
    }

    public void tick() {
        List<UUID> failed = new ArrayList<>();
        for (var instance : instances.all()) {
            UUID id = instance.match().id();
            if (instance.state() != InstanceState.RUNNING) {
                GuardSquad squad = squads.get(id);
                if (squad != null) squad.pause();
                continue;
            }
            var arena = arenas.require(instance.match().arena());
            if (arena.guards().isEmpty()) continue;
            World world = Bukkit.getWorld(instance.worldName());
            if (world == null) continue;
            try {
                GuardSquad squad = squads.get(id);
                if (squad == null) {
                    squad = new GuardSquad(arena.guards(), instance.match().participants().keySet(),
                            definition -> {
                                var actor = new PaperGuardActor(world, definition, ownedEntities, arena.combat());
                                actors.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).put(definition.id(), actor);
                                return actor;
                            },
                            () -> alarm(id), message -> logger.warning("Match " + id + ": " + message));
                    GuardSquad owned = squad;
                    instances.own(id, () -> { owned.release(); squads.remove(id); actors.remove(id); });
                    squads.put(id, squad);
                    squad.start();
                    if (arena.combat()) for (var guard : arena.guards()) instances.registerCombatGuard(id, guard.id());
                }
                List<GuardPlayer> present = new ArrayList<>();
                for (var member : instance.match().participants().entrySet()) {
                    Player player = Bukkit.getPlayer(member.getKey());
                    if (player == null || !player.isOnline() || player.isDead() || !players.contains(member.getKey())
                            || !instances.activePlayer(member.getKey())
                            || player.getGameMode() != GameMode.ADVENTURE || !player.getWorld().equals(world)) continue;
                    present.add(new GuardPlayer(member.getKey(), PaperGuardActor.position(player.getLocation()),
                            PaperGuardActor.position(player.getEyeLocation()), player.isSneaking(), player.isSprinting(), member.getValue().role()));
                }
                Optional<Position> noise = Optional.empty();
                var heist = instances.heistSnapshot(id);
                if (heist.isPresent() && heist.orElseThrow().drillStarted() && !heist.orElseThrow().drillComplete()
                        && !heist.orElseThrow().jammed()) noise = arena.heist().map(h -> h.drill().center());
                squad.tick(present, noise, instance.match().alarm());
            } catch (Exception failure) {
                logger.log(java.util.logging.Level.SEVERE, "Guard update failed for match " + id, failure);
                failed.add(id);
            }
        }
        // stop() finalizes instances; defer it until this frame's snapshot traversal is complete.
        for (UUID id : failed) {
            if (instances.all().stream().anyMatch(instance -> instance.match().id().equals(id))) {
                instances.stop(id, "guard_system_failure");
            }
        }
    }

    public List<String> inspect(UUID id) {
        instances.snapshot(id);
        GuardSquad squad = squads.get(id);
        if (squad == null) return List.of("No active guard squad (guards spawn when a guarded arena starts).");
        return squad.snapshots().stream().map(state -> state.id() + " " + state.decision().state()
                + " suspicion=" + Math.round(state.decision().suspicion() * 100) + "%"
                + " target=" + state.decision().targetId().map(UUID::toString).orElse("none")
                + " updates=" + state.updates() + " pathFailures=" + state.pathFailures()
                + " lastUpdateMicros=" + state.updateMicros()).toList();
    }

    Map<String, PaperGuardActor> actors(UUID id) { return actors.getOrDefault(id, Map.of()); }
    List<GuardDiagnostic> decisions(UUID id) {
        var squad = squads.get(id);
        return squad == null ? List.of() : squad.snapshots();
    }
    void reinforce(UUID id) throws java.io.IOException {
        var arena = arenas.require(instances.snapshot(id).match().arena());
        var squad = squads.get(id);
        if (squad == null) throw new IllegalStateException("Missing combat squad");
        List<dev.gameheist.domain.npc.GuardDefinition> wave = new ArrayList<>();
        int count = Math.min(2, instances.snapshot(id).match().participants().size());
        for (int i = 0; i < count; i++) {
            var base = arena.guards().get(i % arena.guards().size());
            String name = "responder_" + i;
            // Select a fresh ID even for user-authored guard names.
            while (actors(id).containsKey(name)) name += "_r";
            wave.add(new dev.gameheist.domain.npc.GuardDefinition(name, base.patrol(), base.sightRange(), base.fieldOfView(),
                    base.detectionTime(), base.alarmTime(), base.searchTime(), base.speed()));
        }
        squad.reinforce(wave);
        for (var guard : wave) instances.registerCombatGuard(id, guard.id());
    }

    private void alarm(UUID id) {
        if (instances.snapshot(id).match().alarm() == AlarmState.LOUD) return;
        instances.raiseAlarm(id);
        logger.info("Match " + id + ": guard raised alarm");
        for (UUID playerId : instances.snapshot(id).match().participants().keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(Component.text("ALARM! A guard called for help. Your objective progress is preserved.", NamedTextColor.RED));
            }
        }
    }

    @EventHandler(ignoreCancelled = true) public void onTarget(EntityTargetLivingEntityEvent event) {
        if (ownedEntities.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void onDamage(EntityDamageEvent event) {
        if (ownedEntities.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent attack && ownedEntities.contains(attack.getDamager().getUniqueId())) {
            event.setCancelled(true);
        }
    }
    @EventHandler(ignoreCancelled = true) public void onBlockChange(EntityChangeBlockEvent event) {
        if (ownedEntities.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }
}
