package dev.gameheist.domain.match;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.player.Loadout;
import java.time.Instant;
import java.util.*;

public record MatchResult(UUID matchId, ArenaKey arena, Difficulty difficulty, long seed,
                          boolean practice, MatchOutcome outcome, String reason,
                          Instant createdAt, Instant finishedAt, AlarmState alarm,
                          Map<UUID, Loadout> participants, Set<String> completedObjectives, int securedBags) {
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
        if (securedBags < 0) throw new IllegalArgumentException("Negative secured bags");
        participants = Map.copyOf(participants);
        completedObjectives = Set.copyOf(completedObjectives);
    }
}
