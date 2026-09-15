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
    @Test void bestRecordFollowsEvictionWithoutRetriesRefreshingRetention() {
        var repository = new InMemoryResultRepository(2);
        var fast = timedWin(60000);
        var slow = timedWin(90000);
        repository.save(fast).toCompletableFuture().join();
        repository.save(slow).toCompletableFuture().join();
        repository.save(fast).toCompletableFuture().join();
        assertEquals(60000, repository.statistics(player, scope).toCompletableFuture().join().bestWinMillis().orElseThrow());
        repository.save(result(MatchOutcome.LOST, AlarmState.LOUD, player, true)).toCompletableFuture().join();
        assertEquals(90000, repository.statistics(player, scope).toCompletableFuture().join().bestWinMillis().orElseThrow());
        repository.save(result(MatchOutcome.WON, AlarmState.STEALTH, player, true)).toCompletableFuture().join();
        var remaining = repository.statistics(player, scope).toCompletableFuture().join();
        assertTrue(remaining.bestWinMillis().isEmpty());
        assertEquals(1, remaining.wins());
        assertEquals(2, remaining.runs());
    }
    private MatchResult timedWin(long duration) {
        var base = result(MatchOutcome.WON, AlarmState.LOUD, player, true);
        return new MatchResult(base.matchId(), base.arena(), base.difficulty(), base.seed(), base.practice(),
                base.outcome(), base.reason(), base.createdAt(), base.finishedAt(), base.alarm(), base.participants(),
                base.completedObjectives(), base.securedBags(), Map.of(), OptionalLong.of(duration));
    }
    @Test void bestTimeIgnoresLossesAndUntimedWinsAndSurvivesRetries() {
        var repository = new InMemoryResultRepository(10);
        repository.save(result(MatchOutcome.WON, AlarmState.STEALTH, player, true));
        assertTrue(repository.statistics(player, scope).toCompletableFuture().join().bestWinMillis().isEmpty());
        for (long duration : List.of(90000L, 65000L, 80000L)) {
            var base = result(MatchOutcome.WON, AlarmState.LOUD, player, true);
            var timed = new MatchResult(base.matchId(), base.arena(), base.difficulty(), base.seed(), base.practice(),
                    base.outcome(), base.reason(), base.createdAt(), base.finishedAt(), base.alarm(), base.participants(),
                    base.completedObjectives(), base.securedBags(), Map.of(), OptionalLong.of(duration));
            repository.save(timed).toCompletableFuture().join();
            repository.save(timed).toCompletableFuture().join();
        }
        var loss = result(MatchOutcome.LOST, AlarmState.LOUD, player, true);
        repository.save(new MatchResult(loss.matchId(), loss.arena(), loss.difficulty(), loss.seed(), loss.practice(),
                loss.outcome(), loss.reason(), loss.createdAt(), loss.finishedAt(), loss.alarm(), loss.participants(),
                Set.of(), 0, Map.of(), OptionalLong.of(1)));
        var stats = repository.statistics(player, scope).toCompletableFuture().join();
        assertEquals(65000, stats.bestWinMillis().orElseThrow());
        assertEquals(4, stats.wins());
    }
    @Test void combatTotalsArePersonalRetrySafeAndIncludeHistoricalRuns() {
        var repository = new InMemoryResultRepository(10);
        UUID other = UUID.randomUUID();
        var roster = Map.of(player, Loadout.starter(Role.SCOUT), other, Loadout.starter(Role.SUPPORT));
        var combat = new MatchResult(UUID.randomUUID(), scope.arena(), Difficulty.NORMAL, 1, true,
                MatchOutcome.WON, "combat", Instant.EPOCH, Instant.EPOCH.plusSeconds(20), AlarmState.LOUD,
                roster, Set.of(), 3, Map.of(player, new dev.gameheist.domain.combat.CombatStats(120, 30, 1),
                        other, new dev.gameheist.domain.combat.CombatStats(40, 100, 0)));
        repository.save(combat).toCompletableFuture().join();
        repository.save(combat).toCompletableFuture().join();
        repository.save(new MatchResult(UUID.randomUUID(), scope.arena(), Difficulty.NORMAL, 1, true,
                MatchOutcome.ABORTED, "legacy", Instant.EPOCH, Instant.EPOCH.plusSeconds(20), AlarmState.LOUD,
                roster, Set.of(), 0)).toCompletableFuture().join();
        var crewScope = new StatisticsScope(scope.arena(), Difficulty.NORMAL, 2, true);
        assertEquals(new PlayerStatistics(1, 0, 1, 0, 3, 120, 30, 1), repository.statistics(player, crewScope).toCompletableFuture().join());
        assertEquals(new PlayerStatistics(1, 0, 1, 0, 3, 40, 100, 0), repository.statistics(other, crewScope).toCompletableFuture().join());
        assertThrows(IllegalArgumentException.class, () -> PlayerStatistics.empty().include(combat, UUID.randomUUID()));
        assertThrows(ArithmeticException.class, () -> new PlayerStatistics(0, 0, 0, 0, 0, Long.MAX_VALUE, 0, 0).include(combat, player));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatistics(0, 0, 0, 0, 0, 0, -1, 0));
    }
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
