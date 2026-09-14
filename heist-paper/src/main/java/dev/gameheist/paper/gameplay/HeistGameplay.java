package dev.gameheist.paper.gameplay;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.MatchPhase;
import dev.gameheist.domain.objective.*;
import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.runtime.arena.ArenaRegistry;
import dev.gameheist.runtime.instance.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import java.time.Duration;
import java.util.*;

/** All methods are called on Paper's server thread. Runtime cleanup owns every presentation. */
public final class HeistGameplay {
    private final InstanceManager instances;
    private final ArenaRegistry arenas;
    private final PlayerSessions players;
    private final Map<UUID, Presentation> presentations = new HashMap<>();
    private long ticks;

    public HeistGameplay(InstanceManager instances, ArenaRegistry arenas, PlayerSessions players) {
        this.instances = instances;
        this.arenas = arenas;
        this.players = players;
    }

    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        var instanceId = instances.instanceOf(player.getUniqueId());
        if (instanceId.isEmpty() || !players.contains(player.getUniqueId())) return;
        UUID id = instanceId.orElseThrow();
        var snapshot = instances.snapshot(id);
        if (!player.getWorld().getName().equals(snapshot.worldName()) || player.isDead()
                || player.getGameMode() != GameMode.ADVENTURE) return;
        if (instances.heistSnapshot(id).isEmpty()) return;
        var combat = instances.combatSnapshot(id);
        if (combat.isPresent() && combat.orElseThrow().players().get(player.getUniqueId()).reviving().isPresent()) return;
        Presentation view = view(snapshot);
        long now = System.nanoTime();
        Long previous = view.lastInteraction.get(player.getUniqueId());
        if (previous != null && now - previous < 250_000_000L) return;
        view.lastInteraction.put(player.getUniqueId(), now);
        // Compare with a server ray trace, rather than trusting the packet's clicked block.
        var target = player.getTargetBlockExact(5, FluidCollisionMode.NEVER);
        if (target == null || !target.equals(event.getClickedBlock())) return;
        try {
            String message = instances.interact(player.getUniqueId(),
                    new BlockPosition(target.getX(), target.getY(), target.getZ()), position(player));
            player.sendMessage(Component.text(message, NamedTextColor.AQUA));
        } catch (IllegalStateException | IllegalArgumentException failure) {
            player.sendMessage(Component.text(failure.getMessage(), NamedTextColor.YELLOW));
        }
    }

    public void tick() {
        ticks++;
        for (var instance : instances.all()) {
            if (instance.state() == InstanceState.CLOSING) continue;
            var definition = arenas.require(instance.match().arena()).heist();
            if (definition.isEmpty()) continue;
            Presentation view = view(instance);
            Map<UUID, Position> present = new HashMap<>();
            for (UUID playerId : instance.match().participants().keySet()) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline() && !player.isDead() && players.contains(playerId)
                        && player.getGameMode() == GameMode.ADVENTURE
                        && player.getWorld().getName().equals(instance.worldName())) present.put(playerId, position(player));
            }
            instances.updateHeist(instance.match().id(), present);
            var current = instances.snapshot(instance.match().id());
            var heist = instances.heistSnapshot(instance.match().id()).orElseThrow();
            updateBags(instance, heist, view);
            if (current.match().result().isPresent()) {
                announceResult(current, view);
                continue;
            }
            if (ticks % 5 == 0) render(current, heist, view);
        }
    }

    private Presentation view(InstanceSnapshot instance) {
        UUID id = instance.match().id();
        var existing = presentations.get(id);
        if (existing != null) return existing;
        var created = new Presentation();
        presentations.put(id, created);
        instances.own(id, () -> {
            announceResult(instances.snapshot(id), created);
            for (UUID playerId : created.viewers) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) player.hideBossBar(created.bar);
            }
            presentations.remove(id);
        });
        return created;
    }

    private void updateBags(InstanceSnapshot instance, HeistSnapshot snapshot, Presentation view) {
        World world = Bukkit.getWorld(instance.worldName());
        if (world == null) return;
        for (var bag : snapshot.unavailableBags()) {
            if (view.hiddenBags.add(bag)) world.getBlockAt(bag.x(), bag.y(), bag.z()).setType(Material.AIR, false);
        }
        for (var bag : Set.copyOf(view.hiddenBags)) {
            if (!snapshot.unavailableBags().contains(bag)) {
                world.getBlockAt(bag.x(), bag.y(), bag.z()).setType(Material.GOLD_BLOCK, false);
                view.hiddenBags.remove(bag);
            }
        }
        // Only the walk-speed value owned by PlayerSessions is changed/restored.
        for (UUID playerId : instance.match().participants().keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && players.contains(playerId)) players.carrying(player, snapshot.carriedBags().containsKey(playerId));
        }
    }

    private void render(InstanceSnapshot instance, HeistSnapshot state, Presentation view) {
        String instruction;
        if (instance.match().phase() == MatchPhase.BRIEFING) instruction = "Briefing — waiting for the host to start";
        else if (!state.securityDisabled()) instruction = "Disable security at the blue marker";
        else if (!state.drillStarted()) instruction = "Install the drill at the orange marker";
        else if (state.jammed()) instruction = state.repairingPlayer().isPresent()
                ? "Repairing drill: " + seconds(state.repairRemainingMillis()) + "s — stay close"
                : "DRILL JAMMED — right click the drill to repair";
        else if (!state.drillComplete()) instruction = "Drilling: " + seconds(state.drillRemainingMillis()) + "s remaining";
        else if (state.extracting()) instruction = "EXTRACT IN " + seconds(state.extractionRemainingMillis()) + "s — gather at green";
        else if (state.securedBags() < state.requiredBags()) instruction = "Carry gold bags to green: " + state.securedBags() + "/" + state.requiredBags();
        else instruction = "Minimum loot secured — bring more or vote at green (" + state.extractionVotes() + " votes)";
        view.bar.name(Component.text(instance.match().alarm() + " · " + instruction));
        view.bar.color(state.jammed() ? BossBar.Color.RED : state.extracting() ? BossBar.Color.GREEN : BossBar.Color.BLUE);
        view.bar.progress(Math.min(1f, state.securedBags() / (float) state.requiredBags()));
        var combat = instances.combatSnapshot(instance.match().id());
        for (UUID playerId : instance.match().participants().keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.getWorld().getName().equals(instance.worldName())) continue;
            if (view.viewers.add(playerId)) player.showBossBar(view.bar);
            String status = state.carriedBags().containsKey(playerId) ? "Carrying a bag — right click GREEN to secure it" : instruction;
            if (combat.isPresent()) {
                var fighter = combat.orElseThrow().players().get(playerId);
                if (fighter.downed()) status = "DOWNED — wait for a teammate to revive you";
                else if (fighter.reviving().isPresent()) status = "Reviving: " + seconds(fighter.reviveMillis()) + "s — keep sneaking nearby";
                else status = "HP " + fighter.health() + "/100 · " + (fighter.reloadMillis() > 0
                        ? "Reload " + seconds(fighter.reloadMillis()) + "s" : "Ammo " + fighter.ammunition() + "/12 · F reload") + " · " + status;
            }
            player.sendActionBar(Component.text(status, NamedTextColor.YELLOW));
            if (view.lastPhase != instance.match().phase()) {
                player.sendMessage(Component.text("Heist phase: " + instance.match().phase(), NamedTextColor.AQUA));
            }
        }
        view.lastPhase = instance.match().phase();
    }

    private void announceResult(InstanceSnapshot instance, Presentation view) {
        if (view.resultAnnounced || instance.match().result().isEmpty()) return;
        view.resultAnnounced = true;
        var result = instance.match().result().orElseThrow();
        for (UUID playerId : result.participants().keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) continue;
            player.showTitle(Title.title(Component.text("HEIST " + result.outcome()),
                    Component.text(result.securedBags() + " bags secured · Practice run"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
            player.sendMessage(Component.text("Practice result: " + result.outcome() + " (" + result.reason()
                    + "), " + result.securedBags() + " bags. No progression rewards.", NamedTextColor.AQUA));
            var contribution = result.combatStats().get(playerId);
            if (contribution != null) player.sendMessage(Component.text("Your contribution: " + contribution.revives()
                    + " revives · " + contribution.damageDealt() + " damage dealt · " + contribution.damageTaken() + " damage taken", NamedTextColor.AQUA));
        }
    }
    private static Position position(Player player) {
        var location = player.getLocation();
        return new Position(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }
    private static long seconds(long millis) { return (millis + 999) / 1000; }
    private static final class Presentation {
        private final BossBar bar = BossBar.bossBar(Component.text("Heist"), 0, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        private final Set<UUID> viewers = new HashSet<>();
        private final Set<BlockPosition> hiddenBags = new HashSet<>();
        private final Map<UUID, Long> lastInteraction = new HashMap<>();
        private MatchPhase lastPhase;
        private boolean resultAnnounced;
    }
}
