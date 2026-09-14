package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.player.PlayerProfile;
import java.util.*;
import java.util.concurrent.*;

/** Development adapter. Deliberately not represented as durable player storage. */
public final class InMemoryProfileRepository implements ProfileRepository {
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    @Override public synchronized CompletionStage<PlayerProfile> loadOrCreate(UUID playerId) {
        return CompletableFuture.completedFuture(profiles.computeIfAbsent(playerId, PlayerProfile::starter));
    }
    @Override public synchronized CompletionStage<Void> save(PlayerProfile replacement, long expectedRevision) {
        var current = profiles.get(replacement.playerId());
        if (current == null || current.revision() != expectedRevision
                || expectedRevision == Long.MAX_VALUE || replacement.revision() != expectedRevision + 1) {
            return CompletableFuture.failedFuture(new IllegalStateException("Stale or invalid profile revision"));
        }
        profiles.put(replacement.playerId(), replacement);
        return CompletableFuture.completedFuture(null);
    }
}
