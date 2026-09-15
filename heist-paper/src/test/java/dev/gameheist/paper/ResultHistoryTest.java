package dev.gameheist.paper;

import dev.gameheist.paper.command.ResultHistory;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.arena.ArenaKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultHistoryTest {
    private MatchResult result(int index) {
        return new MatchResult(new UUID(0, index), new ArenaKey("graybox", 4), Difficulty.NORMAL, 1, true,
                MatchOutcome.WON, "extracted", Instant.EPOCH, Instant.EPOCH.plusSeconds(index), AlarmState.LOUD,
                Map.of(), Set.of(), 3, Map.of(), OptionalLong.of(65432));
    }
    @Test void pagesAreNewestFirstBoundedAndDoNotRepeatResults() {
        var results = new ArrayList<MatchResult>();
        for (int i = 0; i < 21; i++) results.add(result(i));
        var first = ResultHistory.page(results, 1);
        assertEquals(3, first.pages());
        assertEquals(10, first.entries().size());
        assertEquals(new UUID(0, 20), first.entries().getFirst().matchId());
        var all = new HashSet<UUID>();
        for (int page = 1; page <= 3; page++) for (var result : ResultHistory.page(results, page).entries())
            assertTrue(all.add(result.matchId()));
        assertEquals(21, all.size());
        assertEquals(1, ResultHistory.page(results, 3).entries().size());
        assertThrows(IllegalArgumentException.class, () -> ResultHistory.page(results, 4));
        assertThrows(IllegalArgumentException.class, () -> ResultHistory.page(results, 0));
    }
    @Test void emptyHistoryAndPreciseTimeHaveExplicitOutput() {
        assertTrue(ResultHistory.page(List.of(), 1).entries().isEmpty());
        assertEquals(1, ResultHistory.page(List.of(), 1).pages());
        assertTrue(ResultHistory.describe(result(1)).contains("time=1:05.432"));
    }
}
