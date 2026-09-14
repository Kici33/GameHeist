package dev.gameheist.runtime;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.match.AlarmState;
import dev.gameheist.domain.npc.*;
import dev.gameheist.domain.player.Role;
import dev.gameheist.runtime.npc.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GuardSquadTest {
    private final UUID member = UUID.randomUUID();
    private final List<FakeActor> actors = new ArrayList<>();
    private final AtomicInteger alarms = new AtomicInteger();
    private final List<String> logs = new ArrayList<>();
    private static Position position(double x, double z) { return new Position(x, 65, z, 0, 0); }

    private GuardDefinition guard(String id) {
        return new GuardDefinition(id, List.of(position(0, 0), position(0, 20)), 12, 90,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 0.65);
    }
    private GuardSquad squad(int count) throws IOException {
        var definitions = new ArrayList<GuardDefinition>();
        for (int i = 0; i < count; i++) definitions.add(guard("guard_" + i));
        var squad = new GuardSquad(definitions, Set.of(member), definition -> {
            var actor = new FakeActor();
            actors.add(actor);
            return actor;
        }, alarms::incrementAndGet, logs::add);
        squad.start();
        return squad;
    }
    private GuardPlayer player(UUID id, Position position, boolean sprinting) {
        return new GuardPlayer(id, position, position, false, sprinting, Role.TECHNICIAN);
    }
    private void ticks(GuardSquad squad, int count, List<GuardPlayer> players) {
        for (int i = 0; i < count; i++) squad.tick(players, Optional.empty(), AlarmState.STEALTH);
    }

    @Test void lineOfSightIsRequiredEvenInsideVisionCone() throws IOException {
        var squad = squad(1);
        actors.getFirst().visible = false;
        ticks(squad, 80, List.of(player(member, position(0, 5), false)));
        assertEquals(0, alarms.get());
        assertEquals(0, squad.snapshots().getFirst().decision().suspicion());
        squad.release();
    }
    @Test void reinforcementsRespectCapAndShareCleanupOwnership() throws IOException {
        var squad = squad(22);
        squad.reinforce(List.of(guard("responder_1"), guard("responder_2")));
        assertEquals(24, squad.snapshots().size());
        assertThrows(IllegalArgumentException.class, () -> squad.reinforce(List.of(guard("overflow"))));
        assertEquals(24, actors.size());
        squad.release();
        assertTrue(actors.stream().allMatch(actor -> actor.releases == 1));
        assertThrows(IllegalStateException.class, () -> squad.reinforce(List.of(guard("late"))));
    }
    @Test void duplicateReinforcementsAreRejectedBeforeSpawning() throws IOException {
        var squad = squad(1);
        assertThrows(IllegalArgumentException.class, () -> squad.reinforce(List.of(guard("new"), guard("guard_0"))));
        assertEquals(1, actors.size());
        squad.release();
    }
    @Test void filtersOtherCrewsAndDoesNotRayTraceOutOfRangePlayers() throws IOException {
        var squad = squad(1);
        ticks(squad, 8, List.of(player(UUID.randomUUID(), position(0, 5), false), player(member, position(0, 30), false)));
        assertEquals(0, actors.getFirst().sightChecks);
        assertEquals(0, alarms.get());
        squad.release();
    }
    @Test void perceptionIsStaggeredAndPathsAreRateLimited() throws IOException {
        var squad = squad(4);
        ticks(squad, 1, List.of());
        assertEquals(List.of(1L, 0L, 0L, 0L), squad.snapshots().stream().map(GuardDiagnostic::updates).toList());
        ticks(squad, 19, List.of());
        assertTrue(squad.snapshots().stream().allMatch(s -> s.updates() == 5));
        assertTrue(actors.stream().allMatch(actor -> actor.pathCalls == 1));
        squad.release();
    }
    @Test void twoGuardsRaiseOneAlarmAndScopeReleaseIsIdempotent() throws IOException {
        var squad = squad(2);
        ticks(squad, 60, List.of(player(member, position(0, 5), false)));
        assertEquals(1, alarms.get());
        squad.release();
        squad.release();
        ticks(squad, 100, List.of(player(member, position(0, 5), false)));
        assertEquals(1, alarms.get());
        assertTrue(actors.stream().allMatch(actor -> actor.releases == 1));
    }
    @Test void hiddenMovingPlayerDoesNotBecomeANewPathTarget() throws IOException {
        var squad = squad(1);
        ticks(squad, 4, List.of(player(member, position(0, 5), false)));
        actors.getFirst().visible = false;
        ticks(squad, 4, List.of(player(member, position(5, 5), false)));
        assertEquals(position(0, 5), squad.snapshots().getFirst().decision().lastKnown().orElseThrow());
        squad.release();
    }
    @Test void sprintingIsHeardButDoesNotImmediatelyRaiseAlarm() throws IOException {
        var squad = squad(1);
        actors.getFirst().visible = false;
        ticks(squad, 4, List.of(player(member, position(0, -5), true)));
        assertEquals(GuardState.INVESTIGATE, squad.snapshots().getFirst().decision().state());
        assertEquals(position(0, -5), squad.snapshots().getFirst().decision().destination().orElseThrow());
        assertEquals(0, alarms.get());
        squad.release();
    }
    @Test void nearbyDrillNoiseInvestigatesWithoutPlayerIdentity() throws IOException {
        var squad = squad(1);
        squad.tick(List.of(), Optional.of(position(-5, 0)), AlarmState.STEALTH);
        assertEquals(position(-5, 0), squad.snapshots().getFirst().decision().destination().orElseThrow());
        assertTrue(squad.snapshots().getFirst().decision().targetId().isEmpty());
        squad.release();
    }
    @Test void stuckGuardIsRetiredAfterBoundedRetriesWithoutTeleporting() throws IOException {
        var squad = squad(1);
        actors.getFirst().pathSuccess = false;
        ticks(squad, 180, List.of());
        assertEquals(GuardState.RETIRED, squad.snapshots().getFirst().decision().state());
        assertTrue(actors.getFirst().pathCalls <= 9);
        assertTrue(logs.stream().anyMatch(s -> s.contains("stuck guard")));
        assertEquals(1, actors.getFirst().releases);
        squad.release();
        assertEquals(1, actors.getFirst().releases);
    }
    @Test void partialSpawnFailureStillOwnsEarlierActors() throws IOException {
        var actor = new FakeActor();
        var calls = new AtomicInteger();
        var squad = new GuardSquad(List.of(guard("one"), guard("two")), Set.of(member), definition -> {
            if (calls.getAndIncrement() == 0) return actor;
            throw new IOException("spawn rejected");
        }, alarms::incrementAndGet, logs::add);
        assertThrows(IOException.class, squad::start);
        squad.release();
        assertEquals(1, actor.releases);
    }
    @Test void pauseStopsNavigationAndAllFuturePerception() throws IOException {
        var squad = squad(1);
        ticks(squad, 4, List.of(player(member, position(0, 5), false)));
        squad.pause();
        int calls = actors.getFirst().sightChecks;
        ticks(squad, 100, List.of(player(member, position(0, 5), false)));
        assertEquals(calls, actors.getFirst().sightChecks);
        assertTrue(actors.getFirst().stops > 0);
        assertEquals(0, alarms.get());
        squad.release();
    }
    @Test void squadsHaveIndependentAlarmStateAndActors() throws IOException {
        var first = squad(1);
        var second = squad(1);
        ticks(first, 60, List.of(player(member, position(0, 5), false)));
        assertEquals(GuardState.PATROL, second.snapshots().getFirst().decision().state());
        first.release();
        assertTrue(actors.get(1).alive());
        second.release();
    }
    @Test void scoutAndSneakingModifiersAreBoundedAndSlower() {
        var scout = new GuardPlayer(member, position(0, 0), position(0, 0), true, false, Role.SCOUT);
        assertEquals(0.375, scout.suspicionRate(), 1e-9);
        assertEquals(1.4, player(member, position(0, 0), true).suspicionRate(), 1e-9);
    }

    private static final class FakeActor implements GuardActor {
        private boolean visible = true, pathSuccess = true, released;
        private int sightChecks, pathCalls, releases, stops;
        @Override public boolean alive() { return !released; }
        @Override public Position position() { return GuardSquadTest.position(0, 0); }
        @Override public Position eyePosition() { return position(); }
        @Override public boolean canSee(UUID id) { sightChecks++; return visible; }
        @Override public boolean moveTo(Position position, double speed) { pathCalls++; return pathSuccess; }
        @Override public void stop() { stops++; }
        @Override public void lookAt(Position position) {}
        @Override public void label(String text) {}
        @Override public void release() { if (!released) { released = true; releases++; } }
    }
}
