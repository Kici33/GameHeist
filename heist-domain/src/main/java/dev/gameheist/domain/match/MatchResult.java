package dev.gameheist.domain.match;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.player.Loadout;
import dev.gameheist.domain.combat.CombatStats;
import java.time.Instant;
import java.util.*;

public record MatchResult(UUID matchId, ArenaKey arena, Difficulty difficulty, long seed,
                          boolean practice, MatchOutcome outcome, String reason,
                          Instant createdAt, Instant finishedAt, AlarmState alarm,
                          Map<UUID, Loadout> participants, Set<String> completedObjectives, int securedBags,
                          Map<UUID, CombatStats> combatStats, OptionalLong gameplayMillis,
                          Map<UUID, dev.gameheist.domain.player.ObjectiveStats> objectiveStats) {
    public MatchResult(UUID matchId, ArenaKey arena, Difficulty difficulty, long seed,
                       boolean practice, MatchOutcome outcome, String reason, Instant createdAt, Instant finishedAt,
                       AlarmState alarm, Map<UUID, Loadout> participants, Set<String> completedObjectives, int securedBags,
                       Map<UUID, CombatStats> combatStats, OptionalLong gameplayMillis) {
        this(matchId, arena, difficulty, seed, practice, outcome, reason, createdAt, finishedAt, alarm,
                participants, completedObjectives, securedBags, combatStats, gameplayMillis, Map.of());
    }
    public MatchResult(UUID matchId, ArenaKey arena, Difficulty difficulty, long seed,
                       boolean practice, MatchOutcome outcome, String reason, Instant createdAt, Instant finishedAt,
                       AlarmState alarm, Map<UUID, Loadout> participants, Set<String> completedObjectives, int securedBags,
                       Map<UUID, CombatStats> combatStats) {
        this(matchId, arena, difficulty, seed, practice, outcome, reason, createdAt, finishedAt,
                alarm, participants, completedObjectives, securedBags, combatStats, OptionalLong.empty());
    }
    public MatchResult(UUID matchId, ArenaKey arena, Difficulty difficulty, long seed,
                       boolean practice, MatchOutcome outcome, String reason, Instant createdAt, Instant finishedAt,
                       AlarmState alarm, Map<UUID, Loadout> participants, Set<String> completedObjectives, int securedBags) {
        this(matchId, arena, difficulty, seed, practice, outcome, reason, createdAt, finishedAt,
                alarm, participants, completedObjectives, securedBags, Map.of());
    }
    public MatchResult(UUID matchId, ArenaKey arena, Difficulty difficulty, long seed,
                       boolean practice, MatchOutcome outcome, String reason, Instant createdAt, Instant finishedAt,
                       AlarmState alarm, Map<UUID, Loadout> participants, Set<String> completedObjectives) {
        this(matchId, arena, difficulty, seed, practice, outcome, reason, createdAt, finishedAt,
                alarm, participants, completedObjectives, 0);
    }
    public MatchResult {
        Objects.requireNonNull(matchId);
        Objects.requireNonNull(arena);
        Objects.requireNonNull(difficulty);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(finishedAt);
        Objects.requireNonNull(alarm);
        Objects.requireNonNull(gameplayMillis);
        if (gameplayMillis.isPresent() && gameplayMillis.getAsLong() < 0) throw new IllegalArgumentException("Negative gameplay time");
        if (securedBags < 0) throw new IllegalArgumentException("Negative secured bags");
        participants = Map.copyOf(participants);
        combatStats = Map.copyOf(combatStats);
        objectiveStats = Map.copyOf(objectiveStats);
        if (!participants.keySet().containsAll(objectiveStats.keySet())) throw new IllegalArgumentException("Objectives outside crew");
        long personalBags = 0;
        for (var contribution : objectiveStats.values()) personalBags = Math.addExact(personalBags, contribution.securedBags());
        if (personalBags > securedBags) throw new IllegalArgumentException("Personal bags exceed crew total");
        if (!participants.keySet().containsAll(combatStats.keySet())) throw new IllegalArgumentException("Combat stats outside crew");
        completedObjectives = Set.copyOf(completedObjectives);
    }
}
