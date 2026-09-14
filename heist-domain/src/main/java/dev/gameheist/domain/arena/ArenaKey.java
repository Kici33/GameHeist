package dev.gameheist.domain.arena;

import dev.gameheist.domain.Checks;

public record ArenaKey(String id, int version) {
    public ArenaKey {
        Checks.id(id);
        if (version < 1) throw new IllegalArgumentException("Arena version must be positive");
    }
    @Override public String toString() { return id + ":" + version; }
}
