package dev.gameheist.domain.npc;

import dev.gameheist.domain.arena.Position;
import java.util.Objects;
import java.util.UUID;

/** Only a currently visible player may be represented here. Noise carries no player identity. */
public record GuardObservation(UUID playerId, Position position, double suspicionRate) {
    public GuardObservation {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(position);
        if (!Double.isFinite(suspicionRate) || suspicionRate < 0.25 || suspicionRate > 2) {
            throw new IllegalArgumentException("Suspicion rate must be 0.25–2");
        }
    }
}
