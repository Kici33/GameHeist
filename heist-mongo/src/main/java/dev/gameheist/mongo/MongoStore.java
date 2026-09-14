package dev.gameheist.mongo;

import com.mongodb.*;
import com.mongodb.client.*;
import dev.gameheist.domain.player.PlayerProfile;
import dev.gameheist.domain.player.PlayerStatistics;
import dev.gameheist.domain.player.StatisticsScope;
import dev.gameheist.domain.match.MatchResult;
import dev.gameheist.runtime.persistence.*;
import org.bson.Document;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static com.mongodb.client.model.Filters.*;

/** Bounded blocking-driver workers; no database call runs on the game thread. */
public final class MongoStore implements ProfileRepository, ResultRepository, StatisticsRepository, AutoCloseable {
    private final MongoClient client;
    private final MongoCollection<Document> profiles;
    private final MongoCollection<Document> results;
    private final MongoReservations reservations;
    private boolean statisticsIndexReady;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("heist-storage-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public MongoStore(String uri, String database) {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("HEIST_MONGODB_URI is required for MongoDB storage");
        if (database == null || !database.matches("[a-zA-Z0-9_-]{1,63}")) throw new IllegalArgumentException("Invalid MongoDB database name");
        var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .applicationName("GameHeist").writeConcern(WriteConcern.MAJORITY)
                .readPreference(ReadPreference.primary()).readConcern(ReadConcern.MAJORITY)
                .timeout(5, TimeUnit.SECONDS)
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS))
                .applyToConnectionPoolSettings(b -> b.maxSize(4)).build();
        client = MongoClients.create(settings);
        profiles = client.getDatabase(database).getCollection("profiles");
        results = client.getDatabase(database).getCollection("match_results");
        reservations = new MongoReservations(client.getDatabase(database).getCollection("reservations"), this);
    }
    @Override public CompletionStage<PlayerProfile> loadOrCreate(UUID playerId) {
        return submit(() -> {
            var query = eq("_id", playerId.toString());
            var existing = profiles.find(query).first();
            if (existing != null) return MongoDocuments.profile(existing);
            var starter = PlayerProfile.starter(playerId);
            try { profiles.insertOne(MongoDocuments.profile(starter)); }
            catch (MongoWriteException failure) { if (!duplicate(failure)) throw failure; }
            return MongoDocuments.profile(profiles.find(query).first());
        });
    }
    @Override public CompletionStage<Void> save(PlayerProfile replacement, long expectedRevision) {
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE || replacement.revision() != expectedRevision + 1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid profile revision"));
        }
        return submit(() -> {
            var document = MongoDocuments.profile(replacement);
            var outcome = profiles.replaceOne(and(eq("_id", replacement.playerId().toString()),
                    eq("schemaVersion", 1), eq("revision", expectedRevision)), document);
            // An identical retry after an ambiguous acknowledgement is safe; never overwrite a newer revision.
            if (outcome.getMatchedCount() == 0 && !document.equals(profiles.find(eq("_id", replacement.playerId().toString())).first())) {
                throw new IllegalStateException("Stale profile revision; reload before editing");
            }
            return null;
        });
    }
    @Override public CompletionStage<Void> save(MatchResult result) {
        return submit(() -> {
            var document = MongoDocuments.result(result);
            try { results.insertOne(document); }
            catch (MongoWriteException failure) {
                if (!duplicate(failure)) throw failure;
                if (!document.equals(results.find(eq("_id", result.matchId().toString())).first())) {
                    throw new IllegalStateException("Conflicting match result", failure);
                }
            }
            return null;
        });
    }
    private static boolean duplicate(MongoWriteException failure) {
        return failure.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
    }
    @Override public CompletionStage<PlayerStatistics> statistics(UUID playerId, StatisticsScope scope) {
        return submit(() -> {
            ensureStatisticsIndex();
            var filter = and(eq("schemaVersion", 1), eq("participants.playerId", playerId.toString()),
                    eq("practice", scope.practice()), eq("arena.id", scope.arena().id()),
                    eq("arena.version", scope.arena().version()), eq("difficulty", scope.difficulty().name()),
                    size("participants", scope.crewSize()));
            var group = new Document("_id", null).append("wins", countIf(new Document("$eq", List.of("$outcome", "WON"))))
                    .append("losses", countIf(new Document("$eq", List.of("$outcome", "LOST"))))
                    .append("aborted", countIf(new Document("$eq", List.of("$outcome", "ABORTED"))))
                    .append("stealthWins", countIf(new Document("$and", List.of(
                            new Document("$eq", List.of("$outcome", "WON")), new Document("$eq", List.of("$alarm", "STEALTH"))))))
                    .append("bags", new Document("$sum", "$securedBags"))
                    .append("damageDealt", new Document("$sum", "$personalCombat.damageDealt"))
                    .append("damageTaken", new Document("$sum", "$personalCombat.damageTaken"))
                    .append("revives", new Document("$sum", "$personalCombat.revives"));
            // Filter before grouping: crew contributions must never count toward another player's totals.
            // Missing combatStats on historical results yields zero without dropping the run.
            var personal = new Document("$arrayElemAt", List.of(new Document("$filter", new Document("input",
                    new Document("$ifNull", List.of("$combatStats", List.of())))
                    .append("as", "combat").append("cond", new Document("$eq", List.of("$$combat.playerId", playerId.toString())))), 0));
            var row = results.aggregate(List.of(com.mongodb.client.model.Aggregates.match(filter),
                    new Document("$set", new Document("personalCombat", personal)),
                    new Document("$group", group))).maxTime(3, TimeUnit.SECONDS).first();
            if (row == null) return PlayerStatistics.empty();
            return new PlayerStatistics(number(row, "wins"), number(row, "losses"), number(row, "aborted"),
                    number(row, "stealthWins"), number(row, "bags"), number(row, "damageDealt"),
                    number(row, "damageTaken"), number(row, "revives"));
        });
    }
    private synchronized void ensureStatisticsIndex() {
        if (statisticsIndexReady) return;
        results.createIndex(new Document("participants.playerId", 1).append("practice", 1)
                .append("arena.id", 1).append("arena.version", 1).append("difficulty", 1));
        statisticsIndexReady = true;
    }
    private static Document countIf(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1L, 0L)));
    }
    private static long number(Document row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalStateException("Invalid statistics total");
        return ((Number) value).longValue();
    }
    public ReservationRepository reservations() { return reservations; }
    <T> CompletableFuture<T> submit(Supplier<T> operation) {
        try { return CompletableFuture.supplyAsync(operation, workers); }
        catch (RejectedExecutionException failure) { return CompletableFuture.failedFuture(failure); }
    }
    @Override public void close() {
        // Accepted work drains on workers. Never wait for network timeouts on Paper's owner thread.
        workers.shutdown();
        Thread.ofPlatform().daemon().name("heist-storage-close").start(() -> {
            try {
                while (!workers.awaitTermination(1, TimeUnit.SECONDS)) { /* drain bounded accepted work */ }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { client.close(); }
        });
    }
}
