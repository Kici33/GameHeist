package dev.gameheist.paper.gameplay;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.MatchPhase;
import dev.gameheist.domain.objective.*;
import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.paper.world.HeistProps;
import dev.gameheist.paper.pack.HeistAudio;
import dev.gameheist.paper.pack.AudioTimeline;
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
    private final HeistAudio audio;
    private final Map<UUID, Presentation> presentations = new HashMap<>();
    private long ticks;

    public HeistGameplay(InstanceManager instances, ArenaRegistry arenas, PlayerSessions players, HeistAudio audio) {
        this.instances = instances;
        this.arenas = arenas;
        this.players = players;
        this.audio = audio;
    }

    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        // Slot 2 belongs to the combat medkit; one click must not also operate an objective.
        if (players.contains(player.getUniqueId()) && player.getInventory().getHeldItemSlot() == 1
                && player.getInventory().getItemInMainHand().getType() == Material.PAPER
                && instances.instanceOf(player.getUniqueId()).flatMap(instances::combatSnapshot).isPresent()) return;
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
    public void hitFeedback(InstanceSnapshot instance, UUID shooter, String guard, int health) {
        if (!instance.match().participants().containsKey(shooter)) return;
        view(instance).hits.computeIfAbsent(shooter, ignored -> new HitFeedback()).hit(ticks, guard, health);
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
            var combatState = instances.combatSnapshot(current.match().id());
            var warning = view.waveWarning.poll(current.match().phase().gameplay() && current.match().result().isEmpty(),
                    combatState.isPresent() ? combatState.orElseThrow().waveRemainingMillis() : OptionalLong.empty());
            if (warning.isPresent()) {
                for (UUID playerId : present.keySet()) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null) continue;
                    player.showTitle(Title.title(Component.text("RESPONDERS IN " + warning.getAsLong() + "s", NamedTextColor.GOLD),
                            Component.text("Find cover and prepare your crew"),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(200))));
                    player.sendMessage(Component.text("Responders approaching — find cover!", NamedTextColor.GOLD));
                }
            }
            var drill = definition.orElseThrow().drill();
            World soundWorld = Bukkit.getWorld(current.worldName());
            for (var cue : view.audio.update(ticks, current.match().phase().gameplay() && current.match().result().isEmpty(),
                    current.match().alarm() == dev.gameheist.domain.match.AlarmState.LOUD,
                    heist.drillStarted(), heist.jammed(), heist.drillComplete())) {
                if (soundWorld != null) audio.emit(current, cue == HeistAudio.Cue.ALARM ? null
                        : new Location(soundWorld, drill.x() + .5, drill.y() + .5, drill.z() + .5), cue);
            }
            updateBags(instance, heist, view);
            if (players.customModels() && !view.propsFailed) {
                World world = Bukkit.getWorld(instance.worldName());
                if (world != null) {
                    try {
                        if (view.props == null) view.props = new HeistProps(world, definition.orElseThrow());
                        view.props.update(heist);
                    } catch (RuntimeException failure) {
                        view.propsFailed = true;
                        if (view.props != null) view.props.close();
                        Bukkit.getLogger().warning("GameHeist model props disabled for " + instance.match().id() + ": " + failure);
                    }
                }
            }
            if (current.match().result().isPresent()) {
                announceResult(current, view);
                continue;
            }
            if (ticks % 5 == 0) render(current, heist, view);
        }
    }
    public void returnBag(Player player) {
        var id = instances.instanceOf(player.getUniqueId());
        if (!players.contains(player.getUniqueId()) || id.isEmpty()) return;
        var instance = instances.snapshot(id.orElseThrow());
        if (!instance.match().phase().gameplay() || !instances.activePlayer(player.getUniqueId())
                || instances.heistSnapshot(id.orElseThrow()).isEmpty() || player.isDead()
                || player.getGameMode() != GameMode.ADVENTURE || !player.getWorld().getName().equals(instance.worldName())) return;
        if (instances.returnBag(player.getUniqueId())) {
            updateBags(instance, instances.heistSnapshot(id.orElseThrow()).orElseThrow(), view(instance));
            player.sendMessage(Component.text("Bag returned to its original gold marker. A crewmate can collect it.", NamedTextColor.AQUA));
        } else player.sendActionBar(Component.text("You are not carrying a bag.", NamedTextColor.YELLOW));
    }

    private Presentation view(InstanceSnapshot instance) {
        UUID id = instance.match().id();
        var existing = presentations.get(id);
        if (existing != null) return existing;
        var created = new Presentation();
        presentations.put(id, created);
        instances.own(id, () -> {
            if (created.props != null) created.props.close();
            announceResult(instances.snapshot(id), created);
            for (UUID playerId : created.viewers) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) player.hideBossBar(created.bar);
                BossBar crewBar = created.crewBars.get(playerId);
                if (player != null && crewBar != null) player.hideBossBar(crewBar);
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
        var combat = instances.combatSnapshot(instance.match().id());
        String responders = combat.filter(snapshot -> snapshot.waveRemainingMillis().isPresent())
                .map(snapshot -> " · Responders in " + seconds(snapshot.waveRemainingMillis().orElseThrow()) + "s").orElse("");
        var remaining = instance.match().remainingMillis();
        long secondsLeft = remaining.isPresent() ? seconds(remaining.getAsLong()) : 0;
        String timer = remaining.isPresent() ? " · Time " + secondsLeft / 60 + ":"
                + String.format(Locale.ROOT, "%02d", secondsLeft % 60) : "";
        view.bar.name(Component.text(instance.match().alarm() + timer + " · " + instruction + responders));
        boolean urgent = remaining.isPresent() && remaining.getAsLong() <= 60_000;
        view.bar.color(state.jammed() || urgent ? BossBar.Color.RED : state.extracting() ? BossBar.Color.GREEN : BossBar.Color.BLUE);
        view.bar.progress(Math.min(1f, state.securedBags() / (float) state.requiredBags()));
        List<CrewStatus.Member> crew = new ArrayList<>();
        if (combat.isPresent()) {
            combat.orElseThrow().players().forEach((id, fighter) -> {
                Player teammate = Bukkit.getPlayer(id);
                if (teammate != null) view.crewNames.put(id, teammate.getName());
                boolean available = teammate != null && teammate.isOnline() && players.contains(id)
                        && teammate.getWorld().getName().equals(instance.worldName()) && !teammate.isDead()
                        && teammate.getGameMode() == GameMode.ADVENTURE;
                crew.add(new CrewStatus.Member(id, view.crewNames.getOrDefault(id, "Teammate"), fighter.health(), available));
            });
        }
        for (UUID playerId : instance.match().participants().keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) continue;
            if (!players.contains(playerId) || !player.getWorld().getName().equals(instance.worldName())) {
                player.hideBossBar(view.bar);
                BossBar oldCrewBar = view.crewBars.remove(playerId);
                if (oldCrewBar != null) player.hideBossBar(oldCrewBar);
                view.viewers.remove(playerId);
                continue;
            }
            if (view.viewers.add(playerId)) player.showBossBar(view.bar);
            String status = state.carriedBags().containsKey(playerId) ? "Carrying a bag — right click GREEN to secure it" : instruction;
            if (combat.isPresent()) {
                var fighter = combat.orElseThrow().players().get(playerId);
                if (fighter.downed()) status = ReviveStatus.forRecipient(playerId, combat.orElseThrow(), view.crewNames);
                else if (fighter.reviving().isPresent()) status = "Reviving: " + seconds(fighter.reviveMillis()) + "s — keep sneaking nearby";
                else status = "HP " + fighter.health() + "/100 · " + (fighter.reloadMillis() > 0
                        ? "Reload " + seconds(fighter.reloadMillis()) + "s" : "Ammo " + fighter.ammunition() + "/12 · F reload")
                        + (fighter.medkitAvailable() ? " · Medkit: slot 2" : " · Medkit used")
                        + (state.carriedBags().containsKey(playerId) ? " · BAG → GREEN · Shift+F return" : "");
            }
            String crewStatus = CrewStatus.format(playerId, crew);
            var feedback = view.hits.get(playerId);
            if (feedback != null && combat.isPresent() && !combat.orElseThrow().players().get(playerId).downed()) {
                String hit = feedback.text(ticks);
                if (!hit.isEmpty()) status += " · " + hit;
                else view.hits.remove(playerId);
            }
            if (!crewStatus.isEmpty()) {
                BossBar crewBar = view.crewBars.computeIfAbsent(playerId, ignored -> {
                    var bar = BossBar.bossBar(Component.text(crewStatus), 1, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
                    player.showBossBar(bar);
                    return bar;
                });
                crewBar.name(Component.text(crewStatus));
                crewBar.progress(CrewStatus.healthFraction(playerId, crew));
                crewBar.color(CrewStatus.needsRescue(playerId, crew) ? BossBar.Color.RED : BossBar.Color.GREEN);
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
        private final AudioTimeline audio = new AudioTimeline();
        private final WaveWarning waveWarning = new WaveWarning();
        private final Map<UUID, String> crewNames = new HashMap<>();
        private final Map<UUID, BossBar> crewBars = new HashMap<>();
        private final Map<UUID, HitFeedback> hits = new HashMap<>();
        private final BossBar bar = BossBar.bossBar(Component.text("Heist"), 0, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        private final Set<UUID> viewers = new HashSet<>();
        private final Set<BlockPosition> hiddenBags = new HashSet<>();
        private final Map<UUID, Long> lastInteraction = new HashMap<>();
        private MatchPhase lastPhase;
        private boolean resultAnnounced;
        private HeistProps props;
        private boolean propsFailed;
    }
}
