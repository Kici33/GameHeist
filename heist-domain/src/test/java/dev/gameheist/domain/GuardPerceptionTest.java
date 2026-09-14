package dev.gameheist.domain;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.npc.GuardPerception;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardPerceptionTest {
    private Position position(double x, double y, double z, float yaw) { return new Position(x, y, z, yaw, 0); }
    @Test void usesMinecraftYawAndRejectsTargetsBehindGuard() {
        var north = position(0, 65, 0, 0);
        assertTrue(GuardPerception.inView(north, position(0, 65, 5, 0), 12, 90));
        assertFalse(GuardPerception.inView(north, position(0, 65, -5, 0), 12, 90));
        assertTrue(GuardPerception.inView(position(0, 65, 0, 90), position(-5, 65, 0, 0), 12, 90));
        assertFalse(GuardPerception.inView(position(0, 65, 0, 90), position(5, 65, 0, 0), 12, 90));
    }
    @Test void respectsRangeAndFieldOfViewBoundary() {
        var origin = position(0, 65, 0, 0);
        assertTrue(GuardPerception.inView(origin, position(5, 65, 5, 0), 12, 90));
        assertFalse(GuardPerception.inView(origin, position(6, 65, 5, 0), 12, 90));
        assertFalse(GuardPerception.inView(origin, position(0, 65, 13, 0), 12, 90));
        assertFalse(GuardPerception.inView(origin, position(0, 80, 0, 0), 12, 90));
    }
}
