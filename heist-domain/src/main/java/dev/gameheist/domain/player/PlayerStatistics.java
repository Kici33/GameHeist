package dev.gameheist.domain.player;

import dev.gameheist.domain.match.*;
import dev.gameheist.domain.combat.CombatStats;
import java.util.UUID;

/** Crew bags are shared outcome data, not a claim about this player's individual contribution. */
public record PlayerStatistics(long wins, long losses, long aborted, long stealthWins, long crewSecuredBags,
                               long damageDealt, long damageTaken, long revives) {
    public PlayerStatistics(long wins, long losses, long aborted, long stealthWins, long crewSecuredBags) {
        this(wins, losses, aborted, stealthWins, crewSecuredBags, 0, 0, 0);
    }
    public PlayerStatistics {
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
        return new PlayerStatistics(Math.addExact(wins, result.outcome() == MatchOutcome.WON ? 1 : 0),
                Math.addExact(losses, result.outcome() == MatchOutcome.LOST ? 1 : 0),
                Math.addExact(aborted, result.outcome() == MatchOutcome.ABORTED ? 1 : 0),
                Math.addExact(stealthWins, result.outcome() == MatchOutcome.WON && result.alarm() == AlarmState.STEALTH ? 1 : 0),
                Math.addExact(crewSecuredBags, result.securedBags()),
                Math.addExact(damageDealt, combat.damageDealt()), Math.addExact(damageTaken, combat.damageTaken()),
                Math.addExact(revives, combat.revives()));
    }
}
