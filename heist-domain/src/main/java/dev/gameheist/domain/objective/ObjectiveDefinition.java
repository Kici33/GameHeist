package dev.gameheist.domain.objective;

import dev.gameheist.domain.Checks;
import dev.gameheist.domain.match.MatchPhase;
import java.util.Objects;
import java.util.Set;

public record ObjectiveDefinition(String id, ObjectiveType type, MatchPhase phase,
                                  Set<String> prerequisites, boolean required) {
    public ObjectiveDefinition {
        Checks.id(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(phase);
        if (!phase.gameplay()) throw new IllegalArgumentException("Objective must belong to gameplay");
        prerequisites = Set.copyOf(prerequisites);
        prerequisites.forEach(Checks::id);
        if (prerequisites.contains(id)) throw new IllegalArgumentException("Objective depends on itself: " + id);
    }
}
