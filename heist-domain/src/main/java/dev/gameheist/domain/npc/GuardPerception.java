package dev.gameheist.domain.npc;

import dev.gameheist.domain.arena.Position;

public final class GuardPerception {
    private GuardPerception() {}
    public static double distanceSquared(Position first, Position second) {
        double x = first.x() - second.x(), y = first.y() - second.y(), z = first.z() - second.z();
        return x * x + y * y + z * z;
    }
    /** Minecraft yaw: 0 faces +Z, 90 faces -X. Height affects range; facing uses the horizontal cone. */
    public static boolean inView(Position eye, Position target, double range, double fieldOfView) {
        if (distanceSquared(eye, target) > range * range) return false;
        double dx = target.x() - eye.x(), dz = target.z() - eye.z();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 0.001) return true;
        double yaw = Math.toRadians(eye.yaw());
        double dot = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / horizontal;
        return dot + 1e-9 >= Math.cos(Math.toRadians(fieldOfView / 2));
    }
}
