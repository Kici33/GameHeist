package dev.gameheist.mongo;

import com.mongodb.client.*;
import dev.gameheist.domain.player.*;
import dev.gameheist.runtime.persistence.LeaderboardRepository;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;

/** Disposable, bounded ten-second cache. Every refill derives from immutable results and validity records. */
public final class MongoLeaderboards implements LeaderboardRepository {
    private final MongoStore store;
    private final MongoCollection<Document> results, eligibility;
    private final Map<StatisticsScope, Cached> cache = new LinkedHashMap<>();
    private long generation;
    private boolean indexed;
    MongoLeaderboards(MongoStore store, MongoDatabase database) {
        this.store = store; results = database.getCollection("match_results");
        eligibility = database.getCollection("result_eligibility");
    }
    static List<Bson> eligibilityStages() {
        return List.of(new Document("$lookup", new Document("from", "result_eligibility").append("localField", "_id")
                        .append("foreignField", "_id").append("as", "eligibility")),
                new Document("$match", new Document("eligibility.valid", true).append("eligibility.version", 1)));
    }
    @Override public CompletionStage<List<LeaderboardEntry>> leaderboard(StatisticsScope scope) {
        if (scope.practice()) return CompletableFuture.failedFuture(new IllegalArgumentException("Leaderboard requires production scope"));
        return store.submit(() -> {
            ensureIndex();
            long observed;
            synchronized (this) {
                var cached = cache.get(scope);
                if (cached != null && System.nanoTime() - cached.created < 10_000_000_000L) return cached.entries;
                observed = generation;
            }
            var pipeline = new ArrayList<Bson>();
            pipeline.add(new Document("$match", new Document("practice", false).append("schemaVersion", 1)
                    .append("arena.id", scope.arena().id()).append("arena.version", scope.arena().version())
                    .append("difficulty", scope.difficulty().name()).append("outcome", new Document("$in", List.of("WON", "LOST")))
                    .append("participants", new Document("$size", scope.crewSize()))));
            pipeline.addAll(eligibilityStages());
            pipeline.add(new Document("$unwind", "$participants"));
            pipeline.add(new Document("$group", new Document("_id", "$participants.playerId")
                    .append("wins", new Document("$sum", new Document("$cond", List.of(new Document("$eq", List.of("$outcome", "WON")), 1L, 0L))))
                    .append("playtime", new Document("$sum", "$gameplayMillis"))
                    .append("best", new Document("$min", new Document("$cond", Arrays.asList(
                            new Document("$eq", List.of("$outcome", "WON")), "$gameplayMillis", null))))));
            pipeline.add(new Document("$match", new Document("best", new Document("$ne", null))));
            pipeline.add(new Document("$sort", new Document("best", 1).append("wins", -1).append("_id", 1)));
            pipeline.add(new Document("$limit", 10));
            var rows = new ArrayList<LeaderboardEntry>();
            for (var row : results.aggregate(pipeline).maxTime(3, TimeUnit.SECONDS))
                rows.add(new LeaderboardEntry(UUID.fromString(row.getString("_id")), number(row, "wins"), number(row, "best"), number(row, "playtime")));
            var immutable = List.copyOf(rows);
            synchronized (this) {
                if (observed == generation) {
                    cache.put(scope, new Cached(System.nanoTime(), immutable));
                    while (cache.size() > 64) cache.remove(cache.keySet().iterator().next());
                }
            }
            return immutable;
        });
    }
    /** Trusted administrative API; invalidation is retained independently of immutable result identity. */
    public CompletionStage<Void> invalidate(UUID matchId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 200) throw new IllegalArgumentException("Invalid reason");
        return store.submit(() -> {
            var changed = eligibility.updateOne(eq("_id", matchId.toString()), new Document("$set",
                    new Document("valid", false).append("reason", reason).append("invalidatedAt", new Date())));
            if (changed.getMatchedCount() != 1) throw new IllegalStateException("No authoritative result eligibility record");
            clearCache(); return null;
        });
    }
    public synchronized void clearCache() { generation++; cache.clear(); }
    private synchronized void ensureIndex() {
        if (indexed) return;
        results.createIndex(new Document("practice", 1).append("arena.id", 1).append("arena.version", 1)
                .append("difficulty", 1).append("outcome", 1));
        indexed = true;
    }
    private static long number(Document row, String key) { return ((Number) row.get(key)).longValue(); }
    private record Cached(long created, List<LeaderboardEntry> entries) { }
}
