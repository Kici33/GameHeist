package dev.gameheist.domain;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.objective.*;
import dev.gameheist.domain.player.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HeistRunTest {
    @Test void returningLootAfterAbortCannotChangeFinalResultOrBagOwnership() {
        var match = match(0, 2);
        openVault(match);
        interact(match, first, bags.get(0));
        match.abort("test");
        var before = state(match);
        var result = match.result().orElseThrow();
        assertThrows(IllegalStateException.class, () -> match.returnBag(first));
        assertEquals(before, state(match));
        assertSame(result, match.result().orElseThrow());
    }
    @Test void voluntarilyReturnedBagCanBeCollectedOnceByAnotherCrewMember() {
        var match = match(0, 2);
        openVault(match);
        interact(match, first, bags.get(0));
        assertTrue(match.returnBag(first));
        assertFalse(match.returnBag(first));
        assertFalse(state(match).unavailableBags().contains(bags.get(0)));
        assertEquals(0, state(match).securedBags());
        interact(match, second, bags.get(0));
        interact(match, first, bags.get(0));
        assertFalse(state(match).carriedBags().containsKey(first));
        assertEquals(bags.get(0), state(match).carriedBags().get(second));
        interact(match, second, extraction);
        assertEquals(1, state(match).securedBags());
        assertFalse(match.returnBag(second));
        assertThrows(IllegalStateException.class, () -> match.returnBag(UUID.randomUUID()));
    }
    private final Fixtures.MutableClock clock = new Fixtures.MutableClock();
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    private final BlockPosition security = new BlockPosition(-12, 65, -5);
    private final BlockPosition drill = new BlockPosition(0, 65, 0);
    private final BlockPosition extraction = new BlockPosition(-20, 65, 9);
    private final List<BlockPosition> bags = List.of(new BlockPosition(9, 65, -4),
            new BlockPosition(9, 65, 0), new BlockPosition(9, 65, 4));

    private HeistDefinition definition(int jams) {
        return new HeistDefinition("security", security, "drill", drill, Duration.ofSeconds(10),
                Duration.ofSeconds(6), jams, "loot", bags, 2, "escape", extraction, Duration.ofSeconds(5));
    }
    private Match match(int jams, int crew) {
        var base = Fixtures.arena();
        var arena = new ArenaDefinition(base.key(), base.displayName(), base.bounds(), base.spawn(), base.capacity(),
                base.timeLimit(), base.objectives().stream().map(objective -> new ObjectiveDefinition(objective.id(),
                        switch (objective.id()) {
                            case "drill" -> ObjectiveType.DRILL;
                            case "loot" -> ObjectiveType.LOOT;
                            case "escape" -> ObjectiveType.EXTRACT;
                            default -> ObjectiveType.INTERACT;
                        }, objective.phase(), objective.prerequisites(), objective.required())).toList(), Optional.of(definition(jams)));
        var match = new Match(UUID.randomUUID(), arena, Difficulty.NORMAL, 42, true, clock, LoadoutCatalog.starter());
        match.join(first, Loadout.starter(Role.SCOUT));
        if (crew == 2) match.join(second, Loadout.starter(Role.TECHNICIAN));
        match.start();
        return match;
    }
    private String interact(Match match, UUID player, BlockPosition block) {
        return match.interact(player, block, block.center());
    }
    private void openVault(Match match) {
        interact(match, first, security);
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(10));
        match.updateHeist(Map.of());
    }
    private void deliver(Match match, UUID player, int bag) {
        interact(match, player, bags.get(bag));
        interact(match, player, extraction);
    }
    private HeistSnapshot state(Match match) { return match.heistSnapshot().orElseThrow(); }

    @Test void cannotUseObjectivesBeforeStartOrAsOutsiderOrAtDistance() {
        var match = match(0, 1);
        assertThrows(IllegalStateException.class, () -> interact(match, UUID.randomUUID(), security));
        assertThrows(IllegalStateException.class, () -> match.interact(first, security, extraction.center()));
        assertFalse(state(match).securityDisabled());
        match.abort("test");
        assertThrows(IllegalStateException.class, () -> interact(match, first, security));
    }
    @Test void requiresSecurityAndDrillBeforeLootAndDepositsBeforeExtraction() {
        var match = match(0, 1);
        assertThrows(IllegalStateException.class, () -> interact(match, first, drill));
        assertThrows(IllegalStateException.class, () -> interact(match, first, bags.getFirst()));
        assertThrows(IllegalStateException.class, () -> interact(match, first, extraction));
        assertEquals(MatchPhase.INFILTRATION, match.phase());
    }
    @Test void drillDoesNotFinishEarlyOrRestartOnRepeatedClicks() {
        var match = match(0, 1);
        interact(match, first, security);
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(9));
        interact(match, first, drill);
        match.updateHeist(Map.of());
        assertFalse(state(match).drillComplete());
        clock.advance(Duration.ofSeconds(1));
        match.updateHeist(Map.of());
        assertTrue(state(match).drillComplete());
    }
    @Test void oneBagPerPlayerAndOneOwnerPerBag() {
        var match = match(0, 2);
        openVault(match);
        interact(match, first, bags.getFirst());
        assertThrows(IllegalStateException.class, () -> interact(match, first, bags.get(1)));
        interact(match, second, bags.getFirst());
        assertEquals(Map.of(first, bags.getFirst()), state(match).carriedBags());
        assertEquals(1, state(match).unavailableBags().size());
    }
    @Test void repeatedDepositCannotDuplicateLoot() {
        var match = match(0, 1);
        openVault(match);
        deliver(match, first, 0);
        assertThrows(IllegalStateException.class, () -> interact(match, first, extraction));
        assertEquals(1, state(match).securedBags());
        assertTrue(state(match).carriedBags().isEmpty());
    }
    @Test void minimumLootTransitionsToExtractionButOptionalBagsStillWork() {
        var match = match(0, 1);
        openVault(match);
        deliver(match, first, 0);
        deliver(match, first, 1);
        assertEquals(MatchPhase.EXTRACTION, match.phase());
        deliver(match, first, 2);
        assertEquals(3, state(match).securedBags());
        assertFalse(state(match).extracting());
    }
    @Test void extractionRequiresMajorityAndDoesNotResetDeadlineOnDuplicateVote() {
        var match = match(0, 2);
        openVault(match);
        deliver(match, first, 0);
        deliver(match, second, 1);
        interact(match, first, extraction);
        interact(match, first, extraction);
        assertEquals(1, state(match).extractionVotes());
        assertFalse(state(match).extracting());
        interact(match, second, extraction);
        clock.advance(Duration.ofSeconds(4));
        interact(match, second, extraction);
        match.updateHeist(Map.of(first, extraction.center()));
        assertTrue(match.result().isEmpty());
        clock.advance(Duration.ofSeconds(1));
        match.updateHeist(Map.of(first, extraction.center()));
        assertEquals(MatchOutcome.WON, match.result().orElseThrow().outcome());
        assertEquals(2, match.result().orElseThrow().securedBags());
    }
    @Test void extractionWithoutCrewNearbyResetsAndCanBeRetried() {
        var match = match(0, 1);
        openVault(match);
        deliver(match, first, 0);
        deliver(match, first, 1);
        interact(match, first, extraction);
        clock.advance(Duration.ofSeconds(5));
        match.updateHeist(Map.of(UUID.randomUUID(), extraction.center(), first, drill.center()));
        assertTrue(match.result().isEmpty());
        assertFalse(state(match).extracting());
        assertEquals(0, state(match).extractionVotes());
        interact(match, first, extraction);
        clock.advance(Duration.ofSeconds(5));
        match.updateHeist(Map.of(first, extraction.center()));
        assertEquals(MatchOutcome.WON, match.result().orElseThrow().outcome());
    }
    @Test void matchDeadlineOverridesExtractionCompletion() {
        var match = match(0, 1);
        openVault(match);
        deliver(match, first, 0);
        deliver(match, first, 1);
        interact(match, first, extraction);
        clock.advance(Duration.ofMinutes(20));
        match.updateHeist(Map.of(first, extraction.center()));
        assertEquals(MatchOutcome.LOST, match.result().orElseThrow().outcome());
    }
    @Test void jamFreezesDrillAndRepairRequiresContinuedProximity() {
        var match = match(1, 1);
        interact(match, first, security);
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(10));
        match.updateHeist(Map.of());
        assertTrue(state(match).jammed());
        long remaining = state(match).drillRemainingMillis();
        clock.advance(Duration.ofSeconds(10));
        match.updateHeist(Map.of());
        assertEquals(remaining, state(match).drillRemainingMillis());
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(5));
        match.updateHeist(Map.of(first, extraction.center()));
        assertTrue(state(match).repairingPlayer().isEmpty());
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(5));
        match.updateHeist(Map.of(first, drill.center()));
        assertTrue(state(match).jammed());
        clock.advance(Duration.ofSeconds(1));
        match.updateHeist(Map.of(first, drill.center()));
        assertFalse(state(match).jammed());
        clock.advance(Duration.ofMillis(remaining));
        match.updateHeist(Map.of());
        assertTrue(state(match).drillComplete());
    }
    @Test void technicianRepairsFasterAndSecondClickDoesNotResetRepair() {
        var match = match(1, 2);
        interact(match, first, security);
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(10));
        match.updateHeist(Map.of());
        interact(match, second, drill);
        clock.advance(Duration.ofSeconds(4));
        interact(match, first, drill);
        match.updateHeist(Map.of(second, drill.center()));
        assertTrue(state(match).jammed());
        clock.advance(Duration.ofMillis(500));
        match.updateHeist(Map.of(second, drill.center()));
        assertFalse(state(match).jammed());
    }
    @Test void twoJamsAreSeededCappedAndCannotBeSkippedByLargeTick() {
        var match = match(2, 1);
        var other = match(2, 1);
        for (Match current : List.of(match, other)) {
            interact(current, first, security);
            interact(current, first, drill);
        }
        clock.advance(Duration.ofSeconds(10));
        for (Match current : List.of(match, other)) current.updateHeist(Map.of());
        assertEquals(state(match).drillRemainingMillis(), state(other).drillRemainingMillis());
        assertEquals(1, state(match).jamsTriggered());
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(6));
        match.updateHeist(Map.of(first, drill.center()));
        clock.advance(Duration.ofSeconds(10));
        match.updateHeist(Map.of());
        assertEquals(2, state(match).jamsTriggered());
        assertTrue(state(match).jammed());
        interact(match, first, drill);
        clock.advance(Duration.ofSeconds(6));
        match.updateHeist(Map.of(first, drill.center()));
        clock.advance(Duration.ofSeconds(10));
        match.updateHeist(Map.of());
        assertTrue(state(match).drillComplete());
        assertTrue(state(other).jammed());
    }
    @Test void snapshotsCannotMutateBagOwnership() {
        var match = match(0, 1);
        openVault(match);
        interact(match, first, bags.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> state(match).carriedBags().clear());
        assertThrows(UnsupportedOperationException.class, () -> state(match).unavailableBags().clear());
    }
    @Test void abortPreventsTimersAndLateInteractionsFromChangingResults() {
        var match = match(0, 1);
        interact(match, first, security);
        interact(match, first, drill);
        match.abort("disconnect");
        var result = match.result().orElseThrow();
        clock.advance(Duration.ofMinutes(1));
        match.updateHeist(Map.of());
        assertFalse(state(match).drillComplete());
        assertSame(result, match.result().orElseThrow());
        assertThrows(IllegalStateException.class, () -> interact(match, first, bags.getFirst()));
    }
}
