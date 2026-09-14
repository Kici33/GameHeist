package dev.gameheist.domain.arena;

public record BlockPosition(int x, int y, int z) {
    public Position center() { return new Position(x + 0.5, y + 0.5, z + 0.5, 0, 0); }
    public boolean within(Position position, double radius) {
        double dx = x + 0.5 - position.x(), dy = y + 0.5 - position.y(), dz = z + 0.5 - position.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
