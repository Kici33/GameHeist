package dev.gameheist.mongo;

import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import dev.gameheist.domain.match.MatchResult;
import dev.gameheist.domain.network.BackendAssignment;
import dev.gameheist.domain.player.*;
import org.bson.Document;
import java.util.*;
import java.util.concurrent.CompletionStage;
import static com.mongodb.client.model.Filters.*;

/** Durable outbox plus transactional receipts. Requires a replica set, never standalone MongoDB. */
public final class MongoRewards {
    private static final TransactionOptions TRANSACTION = TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build();
    private final MongoStore store;
    private final MongoClient client;
    private final MongoCollection<Document> reservations, results, jobs, receipts, progression, eligibility;
    private boolean indexed;

    MongoRewards(MongoStore store, MongoClient client, MongoDatabase database) {
        this.store = store; this.client = client;
        reservations = database.getCollection("reservations");
        results = database.getCollection("match_results");
        jobs = database.getCollection("reward_jobs");
        receipts = database.getCollection("reward_receipts");
        progression = database.getCollection("player_progression");
        eligibility = database.getCollection("result_eligibility");
    }

    /** Trusted backend API. Caller identity must come from the transport, not player input. */
    public CompletionStage<Void> accept(UUID reservationId, BackendAssignment caller, MatchResult result) {
        Objects.requireNonNull(reservationId); Objects.requireNonNull(caller); Objects.requireNonNull(result);
        if (result.practice() || !caller.matchId().equals(result.matchId()))
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Not an authoritative production result"));
        return store.submit(() -> {
            indexes();
            var payload = MongoDocuments.result(result);
            long xp = RewardPolicy.experience(result);
            try (var session = client.startSession()) {
                session.withTransaction(() -> {
                    var reservation = reservations.find(session, eq("_id", reservationId.toString())).first();
                    if (reservation == null) throw new IllegalStateException("Missing reservation");
                    var snapshot = ReservationDocuments.snapshot(reservation);
                    if (!snapshot.assignment().equals(Optional.of(caller)) || !snapshot.wholeCrewAdmitted()
                            || !snapshot.request().crew().equals(result.participants())
                            || !snapshot.request().arena().equals(result.arena()) || snapshot.request().difficulty() != result.difficulty())
                        throw new IllegalStateException("Stale backend generation or mismatched roster/arena");
                    var existing = results.find(session, eq("_id", result.matchId().toString())).first();
                    if (existing != null) {
                        if (!existing.equals(payload) || !result.matchId().toString().equals(reservation.getString("rewardResult")))
                            throw new IllegalStateException("Conflicting or unowned result");
                        return null; // An acknowledged identical retry remains valid after reservation release.
                    }
                    if (!snapshot.active()) throw new IllegalStateException("Reservation is no longer active");
                    // A write fences concurrent release/reassignment; a snapshot read alone is insufficient.
                    var fenced = reservations.updateOne(session, and(eq("_id", reservationId.toString()), eq("active", true),
                            eq("assignment", ReservationDocuments.assignment(caller))), Updates.set("rewardResult", result.matchId().toString()));
                    if (fenced.getMatchedCount() != 1) throw new IllegalStateException("Reservation ownership changed");
                    results.insertOne(session, payload);
                    eligibility.insertOne(session, new Document("_id", result.matchId().toString()).append("valid", true)
                            .append("version", 1).append("reservationId", reservationId.toString()));
                    if (xp > 0) for (UUID player : result.participants().keySet()) {
                        String key = result.matchId() + "/" + player + "/" + RewardPolicy.VERSION;
                        jobs.insertOne(session, new Document("_id", key).append("matchId", result.matchId().toString())
                                .append("playerId", player.toString()).append("version", RewardPolicy.VERSION)
                                .append("experience", xp).append("done", false).append("createdAt", Date.from(result.finishedAt())));
                    }
                    return null;
                }, TRANSACTION);
            }
            store.leaderboards().clearCache();
            return null;
        });
    }

    /** Bounded batch; multiple workers may race safely. Pending work survives process death. */
    public CompletionStage<Integer> recover(int limit) {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("Recovery batch must be 1–64");
        return store.submit(() -> {
            indexes();
            var pending = jobs.find(and(eq("done", false), eq("version", RewardPolicy.VERSION),
                            or(exists("retryAfter", false), lte("retryAfter", new Date()))))
                    .sort(new Document("createdAt", 1).append("_id", 1)).limit(limit).into(new ArrayList<>());
            int applied = 0;
            for (var job : pending) {
                try (var session = client.startSession()) {
                    boolean changed = session.withTransaction(() -> {
                        String key = job.getString("_id"), player = job.getString("playerId");
                        var receipt = receipts.find(session, eq("_id", key)).first();
                        var expected = new Document("_id", key).append("playerId", player)
                                .append("matchId", job.getString("matchId")).append("version", RewardPolicy.VERSION)
                                .append("experience", job.getLong("experience"));
                        var result = results.find(session, eq("_id", job.getString("matchId"))).first();
                        if (result == null || result.getBoolean("practice") || !result.getString("outcome").equals("WON")
                                || result.getList("participants", Document.class).stream().noneMatch(p -> player.equals(p.getString("playerId")))
                                || job.getLong("experience") != 100L + 25L * result.getInteger("securedBags"))
                            throw new IllegalStateException("Reward job does not match durable result");
                        if (receipt != null) {
                            if (!expected.equals(receipt)) throw new IllegalStateException("Conflicting reward receipt");
                            jobs.updateOne(session, eq("_id", key), Updates.set("done", true));
                            return false;
                        }
                        receipts.insertOne(session, expected);
                        var updated = progression.findOneAndUpdate(session, eq("_id", player), Updates.combine(
                                Updates.inc("experience", job.getLong("experience")), Updates.inc("wins", 1L),
                                Updates.addToSet("cosmetics", "brass")), new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
                        if (Objects.requireNonNull(updated).getLong("wins") >= 5)
                            progression.updateOne(session, eq("_id", player), Updates.addToSet("cosmetics", "veteran"));
                        jobs.updateOne(session, eq("_id", key), Updates.set("done", true));
                        return true;
                    }, TRANSACTION);
                    if (changed) applied++;
                } catch (RuntimeException failure) {
                    // Do not let one poison job starve later players. An uncertain commit may already be done.
                    jobs.updateOne(and(eq("_id", job.getString("_id")), eq("done", false)), Updates.combine(
                            Updates.set("retryAfter", new Date(System.currentTimeMillis() + 30_000)),
                            Updates.inc("attempts", 1), Updates.set("lastError", "reward_transaction_failed")));
                }
            }
            return applied;
        });
    }

    public CompletionStage<Progression> progression(UUID player) {
        return store.submit(() -> {
            indexes();
            try (var session = client.startSession()) {
                return session.withTransaction(() -> {
                    var row = progression.find(session, eq("_id", player.toString())).first();
                    long pending = jobs.countDocuments(session, and(eq("playerId", player.toString()), eq("done", false)));
                    var cosmetics = new HashSet<>(Set.of("default"));
                    if (row != null) cosmetics.addAll(row.getList("cosmetics", String.class));
                    return row == null ? new Progression(0, 0, cosmetics, pending)
                            : new Progression(row.getLong("experience"), row.getLong("wins"), cosmetics, pending);
                }, TRANSACTION);
            }
        });
    }

    private synchronized void indexes() {
        if (indexed) return;
        jobs.createIndex(new Document("done", 1).append("version", 1).append("createdAt", 1).append("_id", 1));
        jobs.createIndex(new Document("playerId", 1).append("done", 1));
        indexed = true;
    }
}
