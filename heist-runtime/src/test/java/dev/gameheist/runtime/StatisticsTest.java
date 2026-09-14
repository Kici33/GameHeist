package dev.gameheist.runtime;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.player.*;
import dev.gameheist.runtime.persistence.InMemoryResultRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsTest {
    private final UUID player = UUID.randomUUID();
    private final StatisticsScope scope = new StatisticsScope(new ArenaKey("graybox", 3), Difficulty.NORMAL, 1, true);

    @Test void retriesDoNotDoubleCountAndAbortsRemainSeparate() {
        var repository = new InMemoryResultRepository(10);
        var win = result(MatchOutcome.WON, AlarmState.STEALTH, player, true);
        repository.save(win).toCompletableFuture().join();
        repository.save(win).toCompletableFuture().join();
        repository.save(result(MatchOutcome.LOST, AlarmState.LOUD, player, true)).toCompletableFuture().join();
        repository.save(result(MatchOutcome.ABORTED, AlarmState.STEALTH, player, true)).toCompletableFuture().join();
        assertEquals(new PlayerStatistics(1, 1, 1, 1, 9), repository.statistics(player, scope).toCompletableFuture().join());
    }
    @Test void filtersPlayerPracticeMapDifficultyAndCrewSize() {
        var repository = new InMemoryResultRepository(10);
        repository.save(result(MatchOutcome.WON, AlarmState.STEALTH, player, false));
        repository.save(result(MatchOutcome.WON, AlarmState.STEALTH, UUID.randomUUID(), true));
        assertEquals(PlayerStatistics.empty(), repository.statistics(player, scope).toCompletableFuture().join());
        repository.save(result(MatchOutcome.WON, AlarmState.LOUD, player, true));
        for (var other : List.of(new StatisticsScope(new ArenaKey("graybox", 2), Difficulty.NORMAL, 1, true),
                new StatisticsScope(scope.arena(), Difficulty.HARD, 1, true),
                new StatisticsScope(scope.arena(), Difficulty.NORMAL, 2, true))) {
            assertEquals(PlayerStatistics.empty(), repository.statistics(player, other).toCompletableFuture().join());
        }
        assertEquals(new PlayerStatistics(1, 0, 0, 0, 3), repository.statistics(player, scope).toCompletableFuture().join());
    }
    @Test void developmentTotalsFollowBoundedHistoryEviction() {
        var repository = new InMemoryResultRepository(1);
        repository.save(result(MatchOutcome.WON, AlarmState.STEALTH, player, true));
        repository.save(result(MatchOutcome.LOST, AlarmState.LOUD, player, true));
        assertEquals(new PlayerStatistics(0, 1, 0, 0, 3), repository.statistics(player, scope).toCompletableFuture().join());
    }
    @Test void totalsRejectNegativeCountsAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatistics(0, 0, 0, 1, 0));
        assertThrows(ArithmeticException.class, () -> new PlayerStatistics(Long.MAX_VALUE, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new StatisticsScope(scope.arena(), Difficulty.NORMAL, 0, true));
    }
    private MatchResult result(MatchOutcome outcome, AlarmState alarm, UUID participant, boolean practice) {
        return new MatchResult(UUID.randomUUID(), scope.arena(), Difficulty.NORMAL, 1, practice, outcome, "test",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(20), alarm,
                Map.of(participant, Loadout.starter(Role.SCOUT)), Set.of(), 3);
    }
}
