package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.match.MatchResult;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ResultRepository {
    /** Nonblocking, idempotent append. Conflicting results for one ID must fail. */
    CompletionStage<Void> save(MatchResult result);
}
