package dev.gameheist.domain;

import dev.gameheist.domain.match.*;
import dev.gameheist.domain.player.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MatchTest {
    @Test void countdownBeginsAtStartAndUsesTheActualDeadline() {
        assertTrue(match.snapshot().remainingMillis().isEmpty());
        clock.advance(Duration.ofSeconds(20));
        start();
        long duration = Fixtures.arena().timeLimit().toMillis();
        assertEquals(duration, match.snapshot().remainingMillis().orElseThrow());
        clock.advance(Duration.ofMillis(duration - 1));
        assertEquals(1, match.snapshot().remainingMillis().orElseThrow());
        clock.advance(Duration.ofMillis(2));
        assertEquals(0, match.snapshot().remainingMillis().orElseThrow());
        match.tick();
        assertTrue(match.snapshot().remainingMillis().isEmpty());
        assertEquals(MatchOutcome.LOST, match.result().orElseThrow().outcome());
    }
    private final Fixtures.MutableClock clock = new Fixtures.MutableClock();
    private final Match match = Fixtures.match(clock);
    private final UUID player = UUID.randomUUID();

    private void start() { match.join(player, Loadout.starter(Role.SCOUT)); match.start(); }

    @Test void cannotStartEmptyCrew() { assertThrows(IllegalStateException.class, match::start); }
    @Test void cannotJoinTwiceOrAfterStart() {
        match.join(player, Loadout.starter(Role.SCOUT));
        assertThrows(IllegalStateException.class, () -> match.join(player, Loadout.starter(Role.SCOUT)));
        match.start();
        assertThrows(IllegalStateException.class, () -> match.join(UUID.randomUUID(), Loadout.starter(Role.SCOUT)));
    }
    @Test void crewCapacityIsEnforced() {
        for (int i = 0; i < 4; i++) match.join(UUID.randomUUID(), Loadout.starter(Role.SCOUT));
        assertThrows(IllegalStateException.class, () -> match.join(UUID.randomUUID(), Loadout.starter(Role.SCOUT)));
    }
    @Test void unknownEquipmentCannotEnterMatch() {
        assertThrows(IllegalArgumentException.class, () -> match.join(player, new Loadout(Role.SCOUT, "injected", "medkit")));
        assertTrue(match.snapshot().participants().isEmpty());
    }
    @Test void outOfOrderObjectivesDoNotAdvanceState() {
        start();
        assertThrows(IllegalStateException.class, () -> match.completeObjective("drill"));
        match.completeObjective("security");
        assertThrows(IllegalStateException.class, () -> match.completeObjective("loot"));
        assertEquals(MatchPhase.VAULT, match.phase());
    }
    @Test void fullLifecycleCreatesOneImmutablePracticeResult() {
        start();
        match.raiseAlarm();
        for (String objective : new String[]{"security", "drill", "loot", "escape"}) match.completeObjective(objective);
        var result = match.result().orElseThrow();
        assertEquals(MatchOutcome.WON, result.outcome());
        assertEquals(AlarmState.LOUD, result.alarm());
        assertTrue(result.practice());
        assertFalse(match.completeObjective("escape"));
        match.abort("late_abort");
        assertSame(result, match.result().orElseThrow());
        match.close();
        assertEquals(MatchPhase.CLOSED, match.phase());
        assertThrows(IllegalStateException.class, match::start);
    }
    @Test void timeoutAtExactDeadlineWinsOverLateInteraction() {
        start();
        clock.advance(Duration.ofMinutes(20));
        assertThrows(IllegalStateException.class, () -> match.completeObjective("security"));
        assertEquals(MatchOutcome.LOST, match.result().orElseThrow().outcome());
        assertEquals("time_limit", match.result().orElseThrow().reason());
    }
    @Test void briefingDoesNotConsumeGameplayTimer() {
        clock.advance(Duration.ofHours(1));
        start();
        clock.advance(Duration.ofMinutes(19));
        match.tick();
        assertTrue(match.result().isEmpty());
    }
    @Test void arenaAndRosterSnapshotsCannotMutateLiveState() {
        match.join(player, Loadout.starter(Role.SCOUT));
        var before = match.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> before.participants().clear());
        match.join(UUID.randomUUID(), Loadout.starter(Role.SUPPORT));
        assertEquals(1, before.participants().size());
        assertEquals(2, match.snapshot().participants().size());
    }
    @Test void twoMatchesSharingArenaHaveIndependentProgress() {
        var second = Fixtures.match(clock);
        start();
        second.join(UUID.randomUUID(), Loadout.starter(Role.SUPPORT));
        second.start();
        match.completeObjective("security");
        match.raiseAlarm();
        assertEquals(MatchPhase.INFILTRATION, second.phase());
        assertTrue(second.snapshot().completedObjectives().isEmpty());
        assertEquals(AlarmState.STEALTH, second.snapshot().alarm());
    }
    @Test void abortIsTerminalAndIdempotent() {
        match.abort("shutdown");
        match.abort("different_reason");
        assertEquals("shutdown", match.result().orElseThrow().reason());
        assertEquals(MatchOutcome.ABORTED, match.result().orElseThrow().outcome());
        assertThrows(IllegalStateException.class, match::raiseAlarm);
    }
}
