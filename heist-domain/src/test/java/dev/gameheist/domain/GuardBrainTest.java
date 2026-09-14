package dev.gameheist.domain;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.match.AlarmState;
import dev.gameheist.domain.npc.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuardBrainTest {
    private final Position origin = position(0, 0);
    private final Position seen = position(0, 5);
    private final UUID player = UUID.randomUUID();
    private final GuardBrain brain = new GuardBrain(definition());
    private static Position position(double x, double z) { return new Position(x, 65, z, 0, 0); }
    private GuardDefinition definition() {
        return new GuardDefinition("guard", List.of(origin, position(0, 10), position(10, 10)), 12, 100,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 0.65);
    }
    private GuardDecision see(UUID target, double rate, AlarmState alarm) {
        return brain.update(Duration.ofMillis(200), origin,
                Optional.of(new GuardObservation(target, seen, rate)), Optional.empty(), alarm);
    }
    private GuardDecision unseen(Position current, Optional<Position> noise, AlarmState alarm) {
        return brain.update(Duration.ofMillis(200), current, Optional.empty(), noise, alarm);
    }

    @Test void followsPatrolInOrderAndWraps() {
        assertEquals(position(0, 10), unseen(origin, Optional.empty(), AlarmState.STEALTH).destination().orElseThrow());
        assertEquals(position(10, 10), unseen(position(0, 10), Optional.empty(), AlarmState.STEALTH).destination().orElseThrow());
        assertEquals(origin, unseen(position(10, 10), Optional.empty(), AlarmState.STEALTH).destination().orElseThrow());
    }
    @Test void requiresSustainedSightThenTelegraphedAlarmDelay() {
        for (int i = 0; i < 4; i++) assertFalse(see(player, 1, AlarmState.STEALTH).raiseAlarm());
        assertEquals(GuardState.ALERTING, see(player, 1, AlarmState.STEALTH).state());
        for (int i = 0; i < 4; i++) assertFalse(see(player, 1, AlarmState.STEALTH).raiseAlarm());
        var raised = see(player, 1, AlarmState.STEALTH);
        assertTrue(raised.raiseAlarm());
        assertEquals(GuardState.PURSUIT, raised.state());
        assertFalse(see(player, 1, AlarmState.STEALTH).raiseAlarm());
    }
    @Test void losingSightInterruptsAlarmAndUsesOnlyLastSeenPosition() {
        for (int i = 0; i < 8; i++) see(player, 1, AlarmState.STEALTH);
        var lost = unseen(origin, Optional.empty(), AlarmState.STEALTH);
        assertFalse(lost.raiseAlarm());
        assertEquals(GuardState.INVESTIGATE, lost.state());
        assertEquals(seen, lost.destination().orElseThrow());
        assertEquals(seen, lost.lastKnown().orElseThrow());
        see(player, 1, AlarmState.STEALTH);
        for (int i = 0; i < 4; i++) assertFalse(see(player, 1, AlarmState.STEALTH).raiseAlarm());
        assertTrue(see(player, 1, AlarmState.STEALTH).raiseAlarm());
    }
    @Test void searchExpiresAndForgetsTarget() {
        see(player, 1, AlarmState.LOUD);
        assertEquals(GuardState.SEARCH, unseen(seen, Optional.empty(), AlarmState.LOUD).state());
        for (int i = 0; i < 10; i++) unseen(seen, Optional.empty(), AlarmState.LOUD);
        assertEquals(GuardState.PATROL, brain.snapshot().state());
        assertTrue(brain.snapshot().targetId().isEmpty());
        assertTrue(brain.snapshot().lastKnown().isEmpty());
    }
    @Test void targetSwitchCannotInheritAnAlmostCompleteAlarm() {
        for (int i = 0; i < 9; i++) see(player, 1, AlarmState.STEALTH);
        var switched = see(UUID.randomUUID(), 1, AlarmState.STEALTH);
        assertEquals(GuardState.SUSPICIOUS, switched.state());
        assertEquals(0.2, switched.suspicion(), 1e-9);
        assertFalse(switched.raiseAlarm());
    }
    @Test void noiseInvestigatesButNeverCreatesAVisibleTargetOrAlarm() {
        for (int i = 0; i < 30; i++) unseen(origin, Optional.of(seen), AlarmState.STEALTH);
        assertEquals(GuardState.INVESTIGATE, brain.snapshot().state());
        assertEquals(seen, brain.snapshot().destination().orElseThrow());
        assertTrue(brain.snapshot().targetId().isEmpty());
        assertEquals(0, brain.snapshot().suspicion());
        assertFalse(brain.snapshot().raiseAlarm());
    }
    @Test void loudGuardStillNeedsSightToKnowAPlayersLocation() {
        var pursuit = see(player, 1, AlarmState.LOUD);
        assertEquals(GuardState.PURSUIT, pursuit.state());
        assertFalse(pursuit.raiseAlarm());
        var hidden = unseen(origin, Optional.empty(), AlarmState.LOUD);
        assertEquals(GuardState.INVESTIGATE, hidden.state());
        assertEquals(seen, hidden.destination().orElseThrow());
    }
    @Test void lowerSuspicionRateSlowsDetection() {
        for (int i = 0; i < 5; i++) see(player, 0.5, AlarmState.STEALTH);
        assertEquals(GuardState.SUSPICIOUS, brain.snapshot().state());
        assertEquals(0.5, brain.snapshot().suspicion(), 1e-9);
    }
    @Test void retiredGuardCannotMoveOrRaiseAlarm() {
        brain.retire();
        var decision = see(player, 2, AlarmState.LOUD);
        assertEquals(GuardState.RETIRED, decision.state());
        assertTrue(decision.destination().isEmpty());
        assertFalse(decision.raiseAlarm());
    }
    @Test void lagSizedOrNegativeUpdatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> brain.update(Duration.ofSeconds(5), origin,
                Optional.empty(), Optional.empty(), AlarmState.STEALTH));
        assertThrows(IllegalArgumentException.class, () -> brain.update(Duration.ofMillis(-1), origin,
                Optional.empty(), Optional.empty(), AlarmState.STEALTH));
    }
    @Test void brainsShareDefinitionsButNotProgress() {
        var another = new GuardBrain(definition());
        see(player, 1, AlarmState.STEALTH);
        assertEquals(0, another.snapshot().suspicion());
        assertEquals(GuardState.PATROL, another.snapshot().state());
    }
}
