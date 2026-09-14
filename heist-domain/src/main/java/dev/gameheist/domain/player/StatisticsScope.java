package dev.gameheist.domain.player;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.match.MatchResult;
import java.util.Objects;

/** Comparable runs only; practice never contributes to ordinary play totals. */
public record StatisticsScope(ArenaKey arena, Difficulty difficulty, int crewSize, boolean practice) {
    public StatisticsScope {
        Objects.requireNonNull(arena);
        Objects.requireNonNull(difficulty);
        if (crewSize < 1 || crewSize > 4) throw new IllegalArgumentException("Crew size must be 1-4");
    }
    public boolean matches(MatchResult result) {
        return arena.equals(result.arena()) && difficulty == result.difficulty()
                && crewSize == result.participants().size() && practice == result.practice();
    }
}
