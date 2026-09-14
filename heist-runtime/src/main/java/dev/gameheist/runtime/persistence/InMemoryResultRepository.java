package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.match.MatchResult;
import dev.gameheist.domain.player.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded practice history only. Eviction removes deduplication history; never use for rewards. */
public final class InMemoryResultRepository implements ResultRepository, StatisticsRepository {
    private final int capacity;
    private final Map<UUID, MatchResult> results = new LinkedHashMap<>();
    public InMemoryResultRepository(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("History capacity must be positive");
        this.capacity = capacity;
    }
    @Override public synchronized CompletionStage<Void> save(MatchResult result) {
        var previous = results.get(result.matchId());
        if (previous != null && !previous.equals(result)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Conflicting match result"));
        }
        results.put(result.matchId(), result);
        while (results.size() > capacity) results.remove(results.keySet().iterator().next());
        return CompletableFuture.completedFuture(null);
    }
    public synchronized List<MatchResult> all() { return List.copyOf(results.values()); }
    @Override public synchronized CompletionStage<PlayerStatistics> statistics(UUID playerId, StatisticsScope scope) {
        var statistics = PlayerStatistics.empty();
        for (var result : results.values()) {
            if (result.participants().containsKey(playerId) && scope.matches(result)) statistics = statistics.include(result, playerId);
        }
        return CompletableFuture.completedFuture(statistics);
    }
}
