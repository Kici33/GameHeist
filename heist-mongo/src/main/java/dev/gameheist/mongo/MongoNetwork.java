package dev.gameheist.mongo;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.network.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.util.*;
import java.util.concurrent.CompletionStage;
import static com.mongodb.client.model.Filters.*;

/** MongoDB is the routing authority. All methods are for trusted server processes, never player payloads. */
public final class MongoNetwork {
    private final MongoStore store;
    private final MongoClient client;
    private final MongoCollection<Document> servers, reservations;
    private final MongoReservations repository;
    private static final TransactionOptions TX = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
            .writeConcern(WriteConcern.MAJORITY).build();
    MongoNetwork(MongoStore store, MongoClient client, MongoDatabase database, MongoReservations repository) {
        this.store = store; this.client = client; this.repository = repository;
        servers = database.getCollection("network_servers"); reservations = database.getCollection("reservations");
    }
    private static Bson live() { return expr(new Document("$gt", List.of("$leaseUntil", "$$NOW"))); }
    public CompletionStage<Void> heartbeat(String server, UUID incarnation, String role) {
        new BackendAssignment(server, incarnation, UUID.randomUUID(), 1);
        if (!Set.of("lobby", "backend").contains(role)) throw new IllegalArgumentException("Unknown network role");
        return store.submit(() -> {
            var update = List.of(new Document("$set", new Document("incarnation", incarnation.toString()).append("role", role)
                    .append("leaseUntil", new Document("$dateAdd", new Document("startDate", "$$NOW").append("unit", "second").append("amount", 20)))));
            try { servers.insertOne(new Document("_id", server).append("leaseUntil", new Date(0))); }
            catch (MongoWriteException failure) { if (failure.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw failure; }
            if (servers.updateOne(and(eq("_id", server), or(eq("incarnation", incarnation.toString()),
                    expr(new Document("$lte", List.of("$leaseUntil", "$$NOW"))))), update).getMatchedCount() != 1)
                throw new IllegalStateException("Another process owns this backend name");
            return null; // Duplicate _id means another live process owns this proxy server name.
        });
    }
    public CompletionStage<Optional<ReservationSnapshot>> allocate(BackendAssignment target, ArenaKey arena, Difficulty difficulty) {
        return store.submit(() -> {
            repository.ensureIndexes();
            try (var session = client.startSession()) {
                return session.withTransaction(() -> {
                    var fence = servers.updateOne(session, and(eq("_id", target.serverId()), eq("incarnation", target.incarnation().toString()),
                            eq("role", "backend"), live()), Updates.inc("allocationFence", 1L));
                    if (fence.getMatchedCount() != 1) throw new IllegalStateException("Backend lease expired");
                    var occupied = reservations.find(session, and(eq("active", true), eq("assignment.serverId", target.serverId()),
                            eq("assignment.incarnation", target.incarnation().toString()))).first();
                    if (occupied != null) {
                        var snapshot = ReservationDocuments.snapshot(occupied);
                        if (!snapshot.assignment().equals(Optional.of(target))) throw new IllegalStateException("Backend is occupied");
                        return Optional.of(snapshot);
                    }
                    var row = reservations.findOneAndUpdate(session, and(eq("active", true), eq("assigned", false),
                                    eq("arena.id", arena.id()), eq("arena.version", arena.version()), eq("difficulty", difficulty.name()),
                                    expr(new Document("$gt", List.of("$expiresAt", "$$NOW")))),
                            Updates.combine(Updates.set("assigned", true), Updates.set("assignment", ReservationDocuments.assignment(target)),
                                    Updates.set("routingReady", false)),
                            new FindOneAndUpdateOptions().sort(new Document("createdAt", 1).append("_id", 1)).returnDocument(ReturnDocument.AFTER));
                    return Optional.ofNullable(row).map(ReservationDocuments::snapshot);
                }, TX);
            }
        });
    }
    public CompletionStage<Void> ready(UUID reservation, BackendAssignment target) {
        return store.submit(() -> {
            if (reservations.updateOne(and(eq("_id", reservation.toString()), eq("active", true),
                    eq("assignment", ReservationDocuments.assignment(target))), Updates.set("routingReady", true)).getMatchedCount() != 1)
                throw new IllegalStateException("Reservation binding changed");
            return null;
        });
    }
    public record Route(ReservationSnapshot reservation, boolean ready) { }
    public CompletionStage<Optional<Route>> route(UUID player) {
        return store.submit(() -> {
            repository.ensureIndexes();
            var row = reservations.find(and(eq("active", true), eq("crewIds", player.toString()))).first();
            if (row == null) return Optional.empty();
            var snapshot = ReservationDocuments.snapshot(row);
            boolean healthy = snapshot.assignment().map(target -> servers.find(and(eq("_id", target.serverId()),
                    eq("incarnation", target.incarnation().toString()), eq("role", "backend"), live())).first() != null).orElse(false);
            return Optional.of(new Route(snapshot, healthy && row.getBoolean("routingReady", false)));
        });
    }
    public CompletionStage<Optional<String>> lobby() { return lobby(null); }
    public CompletionStage<Optional<String>> lobby(String exclude) {
        return store.submit(() -> Optional.ofNullable(servers.find(and(eq("role", "lobby"), live(), ne("_id", exclude)))
                .sort(new Document("_id", 1)).first()).map(row -> row.getString("_id")));
    }
    /** Reconcile abandoned crews only after their exact backend has lost its database-time lease. */
    public CompletionStage<Boolean> recover(UUID reservationId) {
        return store.submit(() -> {
            try (var session = client.startSession()) {
                return session.withTransaction(() -> {
                    var row = reservations.find(session, and(eq("_id", reservationId.toString()), eq("active", true))).first();
                    if (row == null) return true;
                    var snapshot = ReservationDocuments.snapshot(row);
                    if (snapshot.assignment().isPresent()) {
                        var target = snapshot.assignment().orElseThrow();
                        // Serialize with heartbeat/takeover. A live assigned process must finalize its own instance.
                        var server = servers.findOneAndUpdate(session, eq("_id", target.serverId()), Updates.inc("recoveryFence", 1L),
                                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                        if (server != null && target.incarnation().toString().equals(server.getString("incarnation"))
                                && servers.find(session, and(eq("_id", target.serverId()), live())).first() != null) return false;
                    } else if (reservations.find(session, and(eq("_id", reservationId.toString()),
                            expr(new Document("$gt", List.of("$expiresAt", "$$NOW"))))).first() != null) return false;
                    reservations.updateOne(session, eq("_id", reservationId.toString()), Updates.combine(
                            Updates.set("active", false), Updates.set("routingReady", false), Updates.set("failure", "backend_unavailable_or_queue_expired")));
                    return true;
                }, TX);
            }
        });
    }
}
