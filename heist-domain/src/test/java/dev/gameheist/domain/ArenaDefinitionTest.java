package dev.gameheist.domain;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.MatchPhase;
import dev.gameheist.domain.objective.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.gameheist.domain.Fixtures.objective;

class ArenaDefinitionTest {
    @Test void rejectsDuplicateIdentifiers() {
        var objectives = new ArrayList<>(Fixtures.arena().objectives());
        objectives.add(objectives.getFirst());
        assertThrows(IllegalArgumentException.class, () -> Fixtures.arena(objectives));
    }
    @Test void rejectsMissingPrerequisite() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.arena(List.of(
                objective("a", MatchPhase.INFILTRATION, "missing"),
                objective("b", MatchPhase.VAULT), objective("c", MatchPhase.EXTRACTION))));
    }
    @Test void rejectsCyclesWithinPhase() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.arena(List.of(
                objective("a", MatchPhase.INFILTRATION, "b"), objective("b", MatchPhase.INFILTRATION, "a"),
                objective("c", MatchPhase.VAULT), objective("d", MatchPhase.EXTRACTION))));
    }
    @Test void rejectsDependencyOnFuturePhase() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.arena(List.of(
                objective("a", MatchPhase.INFILTRATION, "b"), objective("b", MatchPhase.VAULT),
                objective("c", MatchPhase.EXTRACTION))));
    }
    @Test void rejectsSkippableEarlierPhaseDependency() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.arena(List.of(
                objective("a", MatchPhase.INFILTRATION),
                new ObjectiveDefinition("optional", ObjectiveType.INTERACT, MatchPhase.INFILTRATION, Set.of(), false),
                objective("b", MatchPhase.VAULT, "optional"), objective("c", MatchPhase.EXTRACTION))));
    }
    @Test void everyPhaseRequiresAnObjective() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.arena(List.of(objective("a", MatchPhase.INFILTRATION))));
    }
    @Test void contentIsDefensivelyCopied() {
        var source = new ArrayList<>(Fixtures.arena().objectives());
        var arena = Fixtures.arena(source);
        source.clear();
        assertEquals(4, arena.objectives().size());
        assertThrows(UnsupportedOperationException.class, () -> arena.objectives().clear());
    }
    @Test void rejectsUnsafeOrInvalidCoordinatesAndIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new ArenaKey("../world", 1));
        assertThrows(IllegalArgumentException.class, () -> new ArenaKey("bank", 0));
        assertThrows(IllegalArgumentException.class, () -> new Position(Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Bounds(new Position(2, 2, 2, 0, 0), new Position(1, 1, 1, 0, 0)));
    }
}
