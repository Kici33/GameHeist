package dev.gameheist.domain.npc;

import dev.gameheist.domain.Checks;
import dev.gameheist.domain.arena.Position;
import java.time.Duration;
import java.util.*;

public record GuardDefinition(String id, List<Position> patrol, double sightRange, double fieldOfView,
                              Duration detectionTime, Duration alarmTime, Duration searchTime, double speed) {
    public GuardDefinition {
        Checks.id(id);
        patrol = List.copyOf(patrol);
        if (patrol.size() < 2 || patrol.size() > 16 || new HashSet<>(patrol).size() != patrol.size()) {
            throw new IllegalArgumentException("Guard patrol requires 2–16 distinct points");
        }
        if (!Double.isFinite(sightRange) || sightRange < 2 || sightRange > 32
                || !Double.isFinite(fieldOfView) || fieldOfView < 30 || fieldOfView > 180
                || !Double.isFinite(speed) || speed < 0.1 || speed > 1.5) {
            throw new IllegalArgumentException("Invalid guard range, field of view, or speed");
        }
        for (Duration duration : List.of(detectionTime, alarmTime, searchTime)) {
            if (duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Guard timers must be between 1 and 30 seconds");
            }
        }
    }
}
