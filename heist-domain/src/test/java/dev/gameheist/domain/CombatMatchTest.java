package dev.gameheist.domain;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.npc.GuardDefinition;
import dev.gameheist.domain.objective.*;
import dev.gameheist.domain.player.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatMatchTest {
    private final Fixtures.MutableClock clock = new Fixtures.MutableClock();
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    private final BlockPosition security = new BlockPosition(-12, 65, -5), drill = new BlockPosition(0, 65, 0),
            exit = new BlockPosition(-20, 65, 9), bag = new BlockPosition(9, 65, 0), optionalBag = new BlockPosition(9, 65, 4);
    private Match match() {
        var base = Fixtures.arena();
        var heist = new HeistDefinition("security", security, "drill", drill, Duration.ofSeconds(10), Duration.ofSeconds(6), 0,
                "loot", List.of(bag, optionalBag), 1, "escape", exit, Duration.ofSeconds(5));
        var objectives = base.objectives().stream().map(o -> new ObjectiveDefinition(o.id(), switch (o.id()) {
            case "drill" -> ObjectiveType.DRILL;
            case "loot" -> ObjectiveType.LOOT;
            case "escape" -> ObjectiveType.EXTRACT;
            default -> ObjectiveType.INTERACT;
        }, o.phase(), o.prerequisites(), o.required())).toList();
        var guard = new GuardDefinition("guard", List.of(new Position(15, 65, 15, 0, 0), new Position(15, 65, 20, 0, 0)),
                16, 100, Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(8), 0.65);
        var arena = new ArenaDefinition(base.key(), base.displayName(), base.bounds(), base.spawn(), 4,
                base.timeLimit(), objectives, Optional.of(heist), List.of(guard), List.of(), true);
        var match = new Match(UUID.randomUUID(), arena, Difficulty.NORMAL, 42, true, clock, LoadoutCatalog.starter());
        match.join(first, Loadout.starter(Role.SCOUT));
        match.join(second, Loadout.starter(Role.SUPPORT));
        match.start();
        match.registerCombatGuard("guard");
        return match;
    }
    private void interact(Match match, UUID player, BlockPosition block) { match.interact(player, block, block.center()); }
    private void open(Match match) {
        interact(match, first, security);
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(10));
        match.updateHeist(Map.of());
    }
    private void down(Match match, UUID player) {
        match.raiseAlarm();
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofMillis(2500));
            match.attack("guard", Optional.of(player), 5, true);
            clock.advance(Duration.ofSeconds(1));
            match.attack("guard", Optional.of(player), 5, true);
        }
    }
    @Test void loudTransitionPreservesObjectivesAndDownedCarrierReturnsExactlyOneBag() {
        var match = match();
        open(match);
        interact(match, first, bag);
        down(match, first);
        assertTrue(match.heistSnapshot().orElseThrow().drillComplete());
        assertEquals(AlarmState.LOUD, match.snapshot().alarm());
        assertTrue(match.heistSnapshot().orElseThrow().carriedBags().isEmpty());
        assertFalse(match.heistSnapshot().orElseThrow().unavailableBags().contains(bag));
        assertThrows(IllegalStateException.class, () -> interact(match, first, bag));
        assertThrows(IllegalStateException.class, () -> match.returnBag(first));
        interact(match, second, bag);
        interact(match, second, exit);
        assertEquals(1, match.heistSnapshot().orElseThrow().securedBags());
        interact(match, second, exit); // One vote is a majority of the one active member.
        clock.advance(Duration.ofSeconds(5));
        match.updateHeist(Map.of(first, exit.center()));
        assertTrue(match.result().isEmpty()); // A downed player cannot satisfy presence at extraction.
        interact(match, second, exit);
        clock.advance(Duration.ofSeconds(5));
        match.updateHeist(Map.of(second, exit.center()));
        assertEquals(MatchOutcome.WON, match.result().orElseThrow().outcome());
        assertEquals(100, match.result().orElseThrow().combatStats().get(first).damageTaken());
    }
    @Test void entireCrewDownIsOneGameplayLossAndTerminalMutationsAreRejected() {
        var match = match();
        down(match, first);
        assertTrue(match.result().isEmpty());
        down(match, second);
        var result = match.result().orElseThrow();
        assertEquals(MatchOutcome.LOST, result.outcome());
        assertEquals("crew_incapacitated", result.reason());
        assertThrows(IllegalStateException.class, () -> match.fire(first, Optional.of("guard"), 5, true));
        assertThrows(IllegalStateException.class, () -> match.beginRevive(first, second, 1, true));
        match.abort("late_abort");
        match.tick();
        assertEquals(result, match.result().orElseThrow());
    }
    @Test void weaponRaisesAlarmAndReviveContributionsAreFrozenIntoResult() {
        var match = match();
        assertTrue(match.fire(first, Optional.of("guard"), 5, true));
        assertEquals(AlarmState.LOUD, match.snapshot().alarm());
        down(match, first);
        assertTrue(match.beginRevive(second, first, 2, true));
        clock.advance(Duration.ofSeconds(3));
        assertTrue(match.updateRevive(second, 2, true, true));
        match.abort("test_complete");
        var stats = match.result().orElseThrow().combatStats();
        assertEquals(20, stats.get(first).damageDealt());
        assertEquals(1, stats.get(second).revives());
        assertThrows(UnsupportedOperationException.class, () -> stats.clear());
    }
    @Test void downingRemovesEarlierExtractionVotes() {
        var match = match();
        open(match);
        interact(match, first, bag);
        interact(match, first, exit);
        interact(match, first, exit);
        assertEquals(1, match.heistSnapshot().orElseThrow().extractionVotes());
        down(match, first);
        assertEquals(0, match.heistSnapshot().orElseThrow().extractionVotes());
        assertFalse(match.heistSnapshot().orElseThrow().extracting());
    }
}
