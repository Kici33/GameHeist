package dev.gameheist.runtime.arena;

import dev.gameheist.domain.arena.*;
import java.util.*;

/** Published versions cannot be overwritten, including while instances reference them. */
public final class ArenaRegistry {
    private final Map<ArenaKey, ArenaDefinition> definitions = new LinkedHashMap<>();
    public void register(ArenaDefinition arena) {
        Objects.requireNonNull(arena);
        if (definitions.putIfAbsent(arena.key(), arena) != null) {
            throw new IllegalArgumentException("Arena version already registered: " + arena.key());
        }
    }
    public ArenaDefinition require(ArenaKey key) {
        var arena = definitions.get(key);
        if (arena == null) throw new IllegalArgumentException("Unknown arena: " + key);
        return arena;
    }
    public List<ArenaDefinition> all() { return List.copyOf(definitions.values()); }
}
