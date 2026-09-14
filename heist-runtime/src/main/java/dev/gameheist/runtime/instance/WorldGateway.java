package dev.gameheist.runtime.instance;

import dev.gameheist.domain.arena.ArenaDefinition;
import java.io.IOException;
import java.util.UUID;

@FunctionalInterface
public interface WorldGateway {
    /** Called on the owner thread. On failure the implementation must roll back partial creation. */
    WorldInstance create(UUID instanceId, ArenaDefinition arena) throws IOException;
}
