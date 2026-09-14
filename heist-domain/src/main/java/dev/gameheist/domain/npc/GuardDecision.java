package dev.gameheist.domain.npc;

import dev.gameheist.domain.arena.Position;
import java.util.*;

/** Pure movement/perception result. targetId and lastKnown never imply current visibility. */
public record GuardDecision(GuardState state, double suspicion, Optional<UUID> targetId,
                            Optional<Position> lastKnown, Optional<Position> destination,
                            Optional<Position> lookAt, boolean raiseAlarm) {}
