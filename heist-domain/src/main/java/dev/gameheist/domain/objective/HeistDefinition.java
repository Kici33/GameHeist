package dev.gameheist.domain.objective;

import dev.gameheist.domain.Checks;
import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.MatchPhase;
import java.time.Duration;
import java.util.*;

/** The physical four-objective graybox recipe. Additional recipes can be introduced separately. */
public record HeistDefinition(String securityObjective, BlockPosition security,
                              String drillObjective, BlockPosition drill, Duration drillDuration,
                              Duration repairDuration, int jams,
                              String lootObjective, List<BlockPosition> bags, int requiredBags,
                              String extractionObjective, BlockPosition extraction, Duration extractionDuration) {
    public HeistDefinition {
        for (String id : List.of(securityObjective, drillObjective, lootObjective, extractionObjective)) Checks.id(id);
        Objects.requireNonNull(security);
        Objects.requireNonNull(drill);
        Objects.requireNonNull(extraction);
        bags = List.copyOf(bags);
        if (bags.isEmpty() || bags.size() > 16 || requiredBags < 1 || requiredBags > bags.size()) {
            throw new IllegalArgumentException("Heist needs 1–16 bags and an achievable required bag count");
        }
        for (Duration duration : List.of(drillDuration, repairDuration, extractionDuration)) {
            if (duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("Objective durations must be between 1 second and 10 minutes");
            }
        }
        if (jams < 0 || jams > 2) throw new IllegalArgumentException("Drill supports 0–2 jams");
        Set<BlockPosition> occupied = new HashSet<>(List.of(security, drill, extraction));
        if (occupied.size() != 3 || bags.stream().anyMatch(p -> !occupied.add(p))) {
            throw new IllegalArgumentException("Heist interaction blocks must have distinct positions");
        }
    }

    public void validate(Bounds bounds, List<ObjectiveDefinition> objectives) {
        for (BlockPosition point : allPositions()) {
            if (!bounds.contains(point.center())) throw new IllegalArgumentException("Heist placement outside arena: " + point);
        }
        if (objectives.size() != 4) throw new IllegalArgumentException("Physical heist recipe requires exactly four objectives");
        require(objectives, securityObjective, ObjectiveType.INTERACT, MatchPhase.INFILTRATION, Set.of());
        require(objectives, drillObjective, ObjectiveType.DRILL, MatchPhase.VAULT, Set.of(securityObjective));
        require(objectives, lootObjective, ObjectiveType.LOOT, MatchPhase.VAULT, Set.of(drillObjective));
        require(objectives, extractionObjective, ObjectiveType.EXTRACT, MatchPhase.EXTRACTION, Set.of(lootObjective));
    }
    public List<BlockPosition> allPositions() {
        List<BlockPosition> positions = new ArrayList<>(List.of(security, drill, extraction));
        positions.addAll(bags);
        return List.copyOf(positions);
    }
    private static void require(List<ObjectiveDefinition> objectives, String id, ObjectiveType type,
                                MatchPhase phase, Set<String> dependencies) {
        var definition = objectives.stream().filter(o -> o.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing heist objective: " + id));
        if (definition.type() != type || definition.phase() != phase || !definition.required()
                || !definition.prerequisites().equals(dependencies)) {
            throw new IllegalArgumentException("Invalid physical heist objective wiring: " + id);
        }
    }
}
