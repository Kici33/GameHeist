package dev.gameheist.mongo;

import com.mongodb.*;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import dev.gameheist.domain.network.*;
import dev.gameheist.domain.player.LoadoutCatalog;
import dev.gameheist.runtime.persistence.ReservationRepository;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;
import static com.mongodb.client.model.Filters.*;

/** One bounded crew document makes membership acquisition atomic without a multi-document transaction. */
public final class MongoReservations implements ReservationRepository {
    private final MongoCollection<Document> reservations;
    private final MongoStore store;
    private boolean indexed;

    MongoReservations(MongoCollection<Document> reservations, MongoStore store) {
        this.reservations = reservations;
        this.store = store;
    }
    @Override public CompletionStage<ReservationSnapshot> reserve(CrewReservation request) {
        return store.submit(() -> {
            request.crew().values().forEach(LoadoutCatalog.starter()::validate);
            ensureIndexes();
            var existing = reservations.find(eq("_id", request.id().toString())).first();
            if (existing != null) return sameRequest(existing, request);
            Instant now = Instant.now();
            if (!request.expiresAt().isAfter(now) || request.createdAt().isAfter(now)) {
                throw new IllegalStateException("Reservation admission window is not current");
            }
            var crewIds = request.crew().keySet().stream().map(UUID::toString).sorted().toList();
            // Never release a partially transferred crew merely because its admission deadline elapsed.
            reservations.updateMany(and(eq("schemaVersion", 1), eq("active", true), in("crewIds", crewIds),
                    eq("claims", new Document()), expr(new Document("$lte", List.of("$expiresAt", "$$NOW")))), Updates.set("active", false));
            var document = ReservationDocuments.request(request).append("active", true).append("assigned", false)
                    .append("claims", new Document());
            try { reservations.insertOne(document); }
            catch (MongoWriteException failure) {
                if (failure.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw failure;
                existing = reservations.find(eq("_id", request.id().toString())).first();
                if (existing == null) throw new IllegalStateException("A crew member already has an active reservation", failure);
                return sameRequest(existing, request);
            }
            return ReservationDocuments.snapshot(document);
        });
    }
    @Override public CompletionStage<Optional<ReservationSnapshot>> find(UUID id) {
        return store.submit(() -> Optional.ofNullable(reservations.find(eq("_id", id.toString())).first()).map(ReservationDocuments::snapshot));
    }
    @Override public CompletionStage<ReservationSnapshot> assign(UUID id, BackendAssignment target) {
        return store.submit(() -> {
            ensureIndexes();
            var assignment = ReservationDocuments.assignment(target);
            return changed(and(current(id), or(eq("assigned", false), eq("assignment", assignment))),
                    Updates.combine(Updates.set("assigned", true), Updates.set("assignment", assignment)));
        });
    }
    @Override public CompletionStage<ReservationSnapshot> admit(UUID id, UUID playerId, BackendAssignment target, UUID attemptId) {
        return store.submit(() -> {
            String claim = "claims." + playerId;
            return changed(and(current(id), eq("assigned", true), eq("assignment", ReservationDocuments.assignment(target)),
                    eq("crewIds", playerId.toString()), or(exists(claim, false), eq(claim, attemptId.toString()))),
                    Updates.set(claim, attemptId.toString()));
        });
    }
    @Override public CompletionStage<ReservationSnapshot> release(UUID id, BackendAssignment target) {
        return store.submit(() -> changed(and(eq("_id", id.toString()), eq("schemaVersion", 1), eq("assigned", true),
                eq("assignment", ReservationDocuments.assignment(target))), Updates.set("active", false)));
    }
    @Override public CompletionStage<ReservationSnapshot> cancelUnclaimed(UUID id) {
        return store.submit(() -> changed(and(eq("_id", id.toString()), eq("schemaVersion", 1), eq("claims", new Document())),
                Updates.set("active", false)));
    }
    private Bson current(UUID id) {
        // Database time is evaluated by the atomic update, not by a delayed worker callback.
        return and(eq("_id", id.toString()), eq("schemaVersion", 1), eq("active", true),
                expr(new Document("$gt", List.of("$expiresAt", "$$NOW"))));
    }
    private ReservationSnapshot changed(Bson filter, Bson update) {
        var document = reservations.findOneAndUpdate(filter, update, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (document == null) throw new IllegalStateException("Reservation is stale, expired, already consumed, or bound to another backend");
        return ReservationDocuments.snapshot(document);
    }
    private static ReservationSnapshot sameRequest(Document document, CrewReservation request) {
        var snapshot = ReservationDocuments.snapshot(document);
        if (!snapshot.request().equals(request)) throw new IllegalStateException("Reservation ID reused with a different request");
        return snapshot;
    }
    private synchronized void ensureIndexes() {
        if (indexed) return;
        reservations.createIndex(Indexes.ascending("crewIds"), new IndexOptions().name("one_active_crew_per_player")
                .unique(true).partialFilterExpression(eq("active", true)));
        reservations.createIndex(Indexes.ascending("assignment.serverId", "assignment.incarnation", "assignment.matchId"),
                new IndexOptions().name("one_active_crew_per_match").unique(true)
                        .partialFilterExpression(and(eq("active", true), eq("assigned", true))));
        indexed = true;
    }
}
