package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.player.PlayerProfile;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ProfileRepository {
    CompletionStage<PlayerProfile> loadOrCreate(UUID playerId);
    /** Replacement revision must equal expectedRevision + 1; stale writes fail. */
    CompletionStage<Void> save(PlayerProfile replacement, long expectedRevision);
}
