package dev.gameheist.runtime.npc;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.player.Role;
import java.util.*;

public record GuardPlayer(UUID id, Position feet, Position eye, boolean sneaking, boolean sprinting, Role role) {
    public GuardPlayer {
        Objects.requireNonNull(id);
        Objects.requireNonNull(feet);
        Objects.requireNonNull(eye);
        Objects.requireNonNull(role);
    }
    public double suspicionRate() {
        return (sneaking ? 0.5 : sprinting ? 1.4 : 1) * (role == Role.SCOUT ? 0.75 : 1);
    }
}
