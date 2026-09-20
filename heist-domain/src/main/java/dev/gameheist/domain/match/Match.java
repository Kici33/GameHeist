package dev.gameheist.domain.match;

import dev.gameheist.domain.arena.ArenaDefinition;
import dev.gameheist.domain.arena.BlockPosition;
import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.objective.HeistRun;
import dev.gameheist.domain.objective.HeistSnapshot;
import dev.gameheist.domain.objective.ObjectiveDefinition;
import dev.gameheist.domain.player.Loadout;
import dev.gameheist.domain.player.LoadoutCatalog;
import dev.gameheist.domain.combat.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Single-owner-thread aggregate. Callers receive immutable views, never its mutable collections. */
public final class Match {
    public static final Duration DISCONNECT_GRACE = Duration.ofSeconds(90);
    public static final Duration VULNERABLE_GRACE = Duration.ofSeconds(10);
    private final UUID id;
    private final ArenaDefinition arena;
    private final Difficulty difficulty;
    private final long seed;
    private final boolean practice;
    private final Clock clock;
    private final Instant createdAt;
    private final LoadoutCatalog catalog;
    private final Map<UUID, Loadout> participants = new LinkedHashMap<>();
    private final Map<UUID, Presence> connections = new LinkedHashMap<>();
    private final Set<String> completed = new LinkedHashSet<>();
    private MatchPhase phase = MatchPhase.BRIEFING;
    private AlarmState alarm = AlarmState.STEALTH;
    private Instant deadline;
    private MatchResult result;
    private final Optional<HeistRun> heist;
    private CombatRun combat;

    public Match(UUID id, ArenaDefinition arena, Difficulty difficulty, long seed,
                 boolean practice, Clock clock, LoadoutCatalog catalog) {
        this.id = Objects.requireNonNull(id);
        this.arena = Objects.requireNonNull(arena);
        this.difficulty = Objects.requireNonNull(difficulty);
        this.seed = seed;
        this.practice = practice;
        this.clock = Objects.requireNonNull(clock);
        this.catalog = Objects.requireNonNull(catalog);
        this.createdAt = clock.instant();
        this.heist = arena.heist().map(definition -> new HeistRun(this, definition, clock, seed));
    }

    public void join(UUID playerId, Loadout loadout) {
        requirePhase(MatchPhase.BRIEFING);
        Objects.requireNonNull(playerId);
        catalog.validate(Objects.requireNonNull(loadout));
        if (participants.containsKey(playerId)) throw new IllegalStateException("Player already joined");
        if (participants.size() >= arena.capacity()) throw new IllegalStateException("Crew is full");
        participants.put(playerId, loadout);
        connections.put(playerId, new Presence());
    }

    public void leaveBriefing(UUID playerId) {
        requirePhase(MatchPhase.BRIEFING);
        if (participants.remove(playerId) == null) throw new IllegalArgumentException("Player is not in crew");
        connections.remove(playerId);
    }

    public void start() {
        requirePhase(MatchPhase.BRIEFING);
        if (participants.isEmpty()) throw new IllegalStateException("Cannot start an empty crew");
        deadline = clock.instant().plus(arena.timeLimit());
        phase = MatchPhase.INFILTRATION;
        if (arena.combat()) combat = new CombatRun(participants, clock, difficulty);
    }

    public void raiseAlarm() {
        requireGameplay();
        alarm = AlarmState.LOUD;
    }

    /** Returns false for an already completed objective, making repeated interactions harmless. */
    public boolean completeObjective(String objectiveId) {
        if (completed.contains(objectiveId)) return false;
        requireGameplay();
        ObjectiveDefinition objective = arena.objectives().stream().filter(o -> o.id().equals(objectiveId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown objective: " + objectiveId));
        if (objective.phase() != phase) throw new IllegalStateException("Objective is not in the current phase");
        if (!completed.containsAll(objective.prerequisites())) throw new IllegalStateException("Prerequisites incomplete");
        completed.add(objectiveId);
        if (arena.objectives().stream().filter(o -> o.phase() == phase && o.required())
                .allMatch(o -> completed.contains(o.id()))) {
            switch (phase) {
                case INFILTRATION -> phase = MatchPhase.VAULT;
                case VAULT -> phase = MatchPhase.EXTRACTION;
                case EXTRACTION -> finish(MatchOutcome.WON, "extracted");
                default -> throw new IllegalStateException("Unexpected phase");
            }
        }
        return true;
    }

    public void tick() {
        expireConnections();
        if (phase.gameplay() && !clock.instant().isBefore(deadline)) finish(MatchOutcome.LOST, "time_limit");
        if (phase.gameplay() && connections.values().stream().noneMatch(Presence::connected)
                && connections.values().stream().noneMatch(Presence::reconnectable)) finish(MatchOutcome.LOST, "crew_disconnected_timeout");
        if (phase.gameplay() && combat != null && participants.keySet().stream().noneMatch(id -> combat.active(id)
                && (connections.get(id).connected() || connections.get(id).reconnectable()))) finish(MatchOutcome.LOST, "crew_incapacitated");
    }

    public void abort(String reason) {
        if (result != null) return;
        finish(MatchOutcome.ABORTED, reason);
    }

    public void close() {
        requirePhase(MatchPhase.FINALIZING);
        phase = MatchPhase.CLOSED;
    }

    public MatchSnapshot snapshot() {
        return new MatchSnapshot(id, arena.key(), phase, alarm, participants, completed, Optional.ofNullable(result),
                phase.gameplay() && deadline != null ? OptionalLong.of(Math.max(0,
                        java.time.Duration.between(clock.instant(), deadline).toMillis())) : OptionalLong.empty(), connectionSnapshot());
    }

    public MatchPhase phase() { return phase; }
    public Difficulty difficulty() { return difficulty; }
    public boolean combatEnabled() { return arena.combat(); }
    public Optional<MatchResult> result() { return Optional.ofNullable(result); }
    public boolean hasParticipant(UUID playerId) { return participants.containsKey(playerId); }
    public int participantCount() { return participants.size(); }
    public Loadout loadout(UUID playerId) {
        var loadout = participants.get(playerId);
        if (loadout == null) throw new IllegalArgumentException("Player is not in crew");
        return loadout;
    }
    public Optional<HeistSnapshot> heistSnapshot() { return heist.map(HeistRun::snapshot); }
    public Optional<CombatSnapshot> combatSnapshot() { return Optional.ofNullable(combat).map(CombatRun::snapshot); }
    public boolean activeParticipant(UUID id) { return hasParticipant(id) && connections.get(id).connected() && (combat == null || combat.active(id)); }
    public int activeParticipantCount() { return (int) participants.keySet().stream().filter(this::activeParticipant).count(); }
    public void registerCombatGuard(String id) { requireCombat().registerGuard(id); }
    public boolean canFire(UUID id) { tick(); return phase.gameplay() && combat != null && combat.canFire(id); }
    public boolean fire(UUID player, Optional<String> hit, double distance, boolean clear) {
        boolean fired = requireCombat().fire(player, hit, distance, clear);
        if (fired) raiseAlarm();
        return fired;
    }
    public boolean reload(UUID player) { return requireCombat().reload(player); }
    public boolean reloadEmpty(UUID player) { return requireCombat().reloadEmpty(player); }
    public boolean useMedkit(UUID player) { return requireCombat().useMedkit(player); }
    public boolean useMedkit(UUID helper, UUID target, double distance, boolean clear) {
        return requireCombat().useMedkit(helper, target, distance, clear);
    }
    public CombatRun.Attack attack(String guard, Optional<UUID> target, double distance, boolean clear) {
        Optional<UUID> targetable = target.filter(id -> {
            Presence presence = requirePresence(id);
            return presence.connected() || presence.status(clock.instant()) == ConnectionStatus.VULNERABLE;
        });
        var outcome = requireCombat().attack(guard, targetable, distance, clear, alarm == AlarmState.LOUD);
        if (outcome == CombatRun.Attack.HIT) {
            target.filter(id -> !activeParticipant(id)).ifPresent(id -> heist.ifPresent(run -> run.incapacitate(id)));
            tick();
        }
        return outcome;
    }
    public boolean beginRevive(UUID helper, UUID target, double distance, boolean clear) {
        return requireCombat().beginRevive(helper, target, distance, clear);
    }
    public boolean updateRevive(UUID helper, double distance, boolean clear, boolean holding) {
        return requireCombat().updateRevive(helper, distance, clear, holding);
    }
    public CombatRun.ReviveUpdate updateReviveDetailed(UUID helper, double distance, boolean clear, boolean holding) {
        return requireCombat().updateReviveDetailed(helper, distance, clear, holding);
    }
    public boolean claimWave() { return claimReinforcements() > 0; }
    public int claimReinforcements() { return requireCombat().claimReinforcements(alarm == AlarmState.LOUD, phase,
            (int) participants.keySet().stream().filter(id -> connections.get(id).connected() && combat.active(id)).count()); }
    public void retireGuard(String id) { requireCombat().retireGuard(id); }
    private CombatRun requireCombat() {
        requireGameplay();
        if (combat == null) throw new IllegalStateException("Combat is not enabled in this arena");
        return combat;
    }
    public String interact(UUID playerId, BlockPosition block, Position position) {
        requireGameplay();
        if (!activeParticipant(playerId)) throw new IllegalStateException("Only active crew members can interact");
        return heist.orElseThrow(() -> new IllegalStateException("Arena has no physical heist layout"))
                .interact(playerId, block, position);
    }
    public boolean returnBag(UUID playerId) {
        requireGameplay();
        if (!activeParticipant(playerId)) throw new IllegalStateException("Only active crew members can return loot");
        return heist.orElseThrow(() -> new IllegalStateException("Arena has no physical heist layout")).returnBag(playerId);
    }
    public void updateHeist(Map<UUID, Position> presentPlayers) {
        tick();
        if (phase.gameplay()) {
            Map<UUID, Position> active = new HashMap<>();
            presentPlayers.forEach((id, position) -> { if (activeParticipant(id)) active.put(id, position); });
            heist.ifPresent(run -> run.tick(Map.copyOf(active)));
        }
    }

    /** Marks an authoritative roster member away. Repeated callbacks for the same generation are harmless. */
    public long disconnect(UUID playerId) {
        requireGameplay();
        Presence presence = requirePresence(playerId);
        if (!presence.connected()) return presence.generation;
        Instant now = clock.instant();
        presence.generation++;
        presence.disconnectedAt = now;
        presence.vulnerableUntil = now.plus(VULNERABLE_GRACE);
        presence.expiresAt = now.plus(DISCONNECT_GRACE);
        heist.ifPresent(run -> run.disconnect(playerId));
        if (combat != null) combat.disconnect(playerId);
        return presence.generation;
    }

    /** Reclaims only the original immutable roster slot; expired slots and stale generations are rejected. */
    public boolean reconnect(UUID playerId, long generation) {
        requireGameplay();
        expireConnections();
        Presence presence = requirePresence(playerId);
        if (presence.connected()) return generation == presence.generation;
        if (!presence.reconnectable() || generation != presence.generation) return false;
        presence.disconnectedAt = presence.vulnerableUntil = presence.expiresAt = null;
        return true;
    }

    public long connectionGeneration(UUID playerId) { return requirePresence(playerId).generation; }

    private Map<UUID, ParticipantConnection> connectionSnapshot() {
        expireConnections();
        Map<UUID, ParticipantConnection> result = new LinkedHashMap<>();
        Instant now = clock.instant();
        connections.forEach((id, value) -> result.put(id, new ParticipantConnection(value.status(now),
                remaining(now, value.vulnerableUntil), remaining(now, value.expiresAt), value.generation)));
        return Map.copyOf(result);
    }
    private void expireConnections() {
        if (!phase.gameplay()) return;
        Instant now = clock.instant();
        connections.values().forEach(value -> {
            if (value.expiresAt != null && !now.isBefore(value.expiresAt)) value.expired = true;
        });
    }
    private Presence requirePresence(UUID playerId) {
        Presence value = connections.get(playerId);
        if (value == null) throw new IllegalArgumentException("Player is not in crew");
        return value;
    }
    private static long remaining(Instant now, Instant deadline) {
        return deadline == null ? 0 : Math.max(0, Duration.between(now, deadline).toMillis());
    }

    private void finish(MatchOutcome outcome, String reason) {
        if (Objects.requireNonNull(reason).isBlank()) throw new IllegalArgumentException("Reason is blank");
        Instant finishedAt = clock.instant();
        result = new MatchResult(id, arena.key(), difficulty, seed, practice, outcome, reason,
                createdAt, finishedAt, alarm, participants, completed,
                heist.map(run -> run.snapshot().securedBags()).orElse(0),
                combat == null ? Map.of() : combat.contributions(),
                deadline == null ? OptionalLong.empty() : OptionalLong.of(Math.max(0,
                        java.time.Duration.between(deadline.minus(arena.timeLimit()), finishedAt).toMillis())),
                heist.map(HeistRun::contributions).orElse(Map.of()));
        phase = MatchPhase.FINALIZING;
    }

    private void requirePhase(MatchPhase expected) {
        if (phase != expected) throw new IllegalStateException("Expected " + expected + ", was " + phase);
    }
    private void requireGameplay() {
        tick();
        if (!phase.gameplay()) throw new IllegalStateException("Match is not accepting gameplay actions");
    }
    private static final class Presence {
        private long generation;
        private Instant disconnectedAt, vulnerableUntil, expiresAt;
        private boolean expired;
        private boolean connected() { return disconnectedAt == null; }
        private boolean reconnectable() { return !connected() && !expired; }
        private ConnectionStatus status(Instant now) {
            if (connected()) return ConnectionStatus.CONNECTED;
            if (expired || !now.isBefore(expiresAt)) return ConnectionStatus.EXPIRED;
            return now.isBefore(vulnerableUntil) ? ConnectionStatus.VULNERABLE : ConnectionStatus.RESERVED;
        }
    }
}
