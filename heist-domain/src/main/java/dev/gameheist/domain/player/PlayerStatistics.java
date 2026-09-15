package dev.gameheist.domain.player;

import dev.gameheist.domain.match.*;
import dev.gameheist.domain.combat.CombatStats;
import java.util.UUID;
import java.util.OptionalLong;
import java.util.Objects;

/** Crew bags are shared outcome data, not a claim about this player's individual contribution. */
public record PlayerStatistics(long wins, long losses, long aborted, long stealthWins, long crewSecuredBags,
                               long damageDealt, long damageTaken, long revives, OptionalLong bestWinMillis,
                               long gameplayMillis, long timedRuns, ObjectiveStats objectiveStats) {
    public PlayerStatistics(long wins, long losses, long aborted, long stealthWins, long crewSecuredBags,
                            long damageDealt, long damageTaken, long revives, OptionalLong bestWinMillis) {
        this(wins, losses, aborted, stealthWins, crewSecuredBags, damageDealt, damageTaken, revives, bestWinMillis, 0, 0, ObjectiveStats.empty());
    }
    public PlayerStatistics(long wins, long losses, long aborted, long stealthWins, long crewSecuredBags,
                            long damageDealt, long damageTaken, long revives) {
        this(wins, losses, aborted, stealthWins, crewSecuredBags, damageDealt, damageTaken, revives, OptionalLong.empty());
    }
    public PlayerStatistics(long wins, long losses, long aborted, long stealthWins, long crewSecuredBags) {
        this(wins, losses, aborted, stealthWins, crewSecuredBags, 0, 0, 0);
    }
    public PlayerStatistics {
        Objects.requireNonNull(bestWinMillis);
        Objects.requireNonNull(objectiveStats);
        if (gameplayMillis < 0 || timedRuns < 0 || timedRuns > Math.addExact(Math.addExact(wins, losses), aborted))
            throw new IllegalArgumentException("Invalid aggregate playtime");
        if (bestWinMillis.isPresent() && (bestWinMillis.getAsLong() < 0 || wins == 0))
            throw new IllegalArgumentException("Invalid best win time");
        if (wins < 0 || losses < 0 || aborted < 0 || stealthWins < 0 || stealthWins > wins || crewSecuredBags < 0
                || damageDealt < 0 || damageTaken < 0 || revives < 0) {
            throw new IllegalArgumentException("Invalid statistics");
        }
        Math.addExact(Math.addExact(wins, losses), aborted);
    }
    public static PlayerStatistics empty() { return new PlayerStatistics(0, 0, 0, 0, 0); }
    public long runs() { return wins + losses + aborted; }
    public PlayerStatistics include(MatchResult result, UUID playerId) {
        if (!result.participants().containsKey(playerId)) throw new IllegalArgumentException("Player did not participate");
        var combat = result.combatStats().getOrDefault(playerId, new CombatStats(0, 0, 0));
        OptionalLong best = bestWinMillis;
        if (result.outcome() == MatchOutcome.WON && result.gameplayMillis().isPresent()) {
            long candidate = result.gameplayMillis().getAsLong();
            best = OptionalLong.of(best.isPresent() ? Math.min(best.getAsLong(), candidate) : candidate);
        }
        return new PlayerStatistics(Math.addExact(wins, result.outcome() == MatchOutcome.WON ? 1 : 0),
                Math.addExact(losses, result.outcome() == MatchOutcome.LOST ? 1 : 0),
                Math.addExact(aborted, result.outcome() == MatchOutcome.ABORTED ? 1 : 0),
                Math.addExact(stealthWins, result.outcome() == MatchOutcome.WON && result.alarm() == AlarmState.STEALTH ? 1 : 0),
                Math.addExact(crewSecuredBags, result.securedBags()),
                Math.addExact(damageDealt, combat.damageDealt()), Math.addExact(damageTaken, combat.damageTaken()),
                Math.addExact(revives, combat.revives()), best,
                Math.addExact(gameplayMillis, result.gameplayMillis().orElse(0)),
                Math.addExact(timedRuns, result.gameplayMillis().isPresent() ? 1 : 0),
                objectiveStats.add(result.objectiveStats().getOrDefault(playerId, ObjectiveStats.empty())));
    }
}
