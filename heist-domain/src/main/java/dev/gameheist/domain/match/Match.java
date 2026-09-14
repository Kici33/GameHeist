package dev.gameheist.domain.match;

import dev.gameheist.domain.arena.ArenaDefinition;
import dev.gameheist.domain.arena.BlockPosition;
import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.objective.HeistRun;
import dev.gameheist.domain.objective.HeistSnapshot;
import dev.gameheist.domain.objective.ObjectiveDefinition;
import dev.gameheist.domain.player.Loadout;
import dev.gameheist.domain.player.LoadoutCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Single-owner-thread aggregate. Callers receive immutable views, never its mutable collections. */
public final class Match {
    private final UUID id;
    private final ArenaDefinition arena;
    private final Difficulty difficulty;
    private final long seed;
    private final boolean practice;
    private final Clock clock;
    private final Instant createdAt;
    private final LoadoutCatalog catalog;
    private final Map<UUID, Loadout> participants = new LinkedHashMap<>();
    private final Set<String> completed = new LinkedHashSet<>();
    private MatchPhase phase = MatchPhase.BRIEFING;
    private AlarmState alarm = AlarmState.STEALTH;
    private Instant deadline;
    private MatchResult result;
    private final Optional<HeistRun> heist;

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
    }

    public void leaveBriefing(UUID playerId) {
        requirePhase(MatchPhase.BRIEFING);
        if (participants.remove(playerId) == null) throw new IllegalArgumentException("Player is not in crew");
    }

    public void start() {
        requirePhase(MatchPhase.BRIEFING);
        if (participants.isEmpty()) throw new IllegalStateException("Cannot start an empty crew");
        deadline = clock.instant().plus(arena.timeLimit());
        phase = MatchPhase.INFILTRATION;
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
        if (phase.gameplay() && !clock.instant().isBefore(deadline)) finish(MatchOutcome.LOST, "time_limit");
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
        return new MatchSnapshot(id, arena.key(), phase, alarm, participants, completed, Optional.ofNullable(result));
    }

    public MatchPhase phase() { return phase; }
    public Difficulty difficulty() { return difficulty; }
    public Optional<MatchResult> result() { return Optional.ofNullable(result); }
    public boolean hasParticipant(UUID playerId) { return participants.containsKey(playerId); }
    public int participantCount() { return participants.size(); }
    public Loadout loadout(UUID playerId) {
        var loadout = participants.get(playerId);
        if (loadout == null) throw new IllegalArgumentException("Player is not in crew");
        return loadout;
    }
    public Optional<HeistSnapshot> heistSnapshot() { return heist.map(HeistRun::snapshot); }
    public String interact(UUID playerId, BlockPosition block, Position position) {
        requireGameplay();
        return heist.orElseThrow(() -> new IllegalStateException("Arena has no physical heist layout"))
                .interact(playerId, block, position);
    }
    public void updateHeist(Map<UUID, Position> presentPlayers) {
        tick();
        if (phase.gameplay()) heist.ifPresent(run -> run.tick(Map.copyOf(presentPlayers)));
    }

    private void finish(MatchOutcome outcome, String reason) {
        if (Objects.requireNonNull(reason).isBlank()) throw new IllegalArgumentException("Reason is blank");
        result = new MatchResult(id, arena.key(), difficulty, seed, practice, outcome, reason,
                createdAt, clock.instant(), alarm, participants, completed,
                heist.map(run -> run.snapshot().securedBags()).orElse(0));
        phase = MatchPhase.FINALIZING;
    }

    private void requirePhase(MatchPhase expected) {
        if (phase != expected) throw new IllegalStateException("Expected " + expected + ", was " + phase);
    }
    private void requireGameplay() {
        tick();
        if (!phase.gameplay()) throw new IllegalStateException("Match is not accepting gameplay actions");
    }
}
