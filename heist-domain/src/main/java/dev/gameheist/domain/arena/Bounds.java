package dev.gameheist.domain.arena;

import java.util.Objects;

public record Bounds(Position minimum, Position maximum) {
    public Bounds {
        Objects.requireNonNull(minimum);
        Objects.requireNonNull(maximum);
        if (minimum.x() > maximum.x() || minimum.y() > maximum.y() || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException("Bounds are inverted");
        }
    }
    public boolean contains(Position p) {
        return p.x() >= minimum.x() && p.x() <= maximum.x()
                && p.y() >= minimum.y() && p.y() <= maximum.y()
                && p.z() >= minimum.z() && p.z() <= maximum.z();
    }
}
