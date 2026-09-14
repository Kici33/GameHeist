package dev.gameheist.domain.match;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.player.Loadout;
import java.util.*;

public record MatchSnapshot(UUID id, ArenaKey arena, MatchPhase phase, AlarmState alarm,
                            Map<UUID, Loadout> participants, Set<String> completedObjectives,
                            Optional<MatchResult> result) {
    public MatchSnapshot {
        participants = Map.copyOf(participants);
        completedObjectives = Set.copyOf(completedObjectives);
        Objects.requireNonNull(result);
    }
}
