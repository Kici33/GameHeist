package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.network.*;
import dev.gameheist.domain.player.LoadoutCatalog;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Process-local development adapter. Retains request identities for retry detection until restart. */
public final class InMemoryReservationRepository implements ReservationRepository {
    private final Map<UUID, ReservationSnapshot> reservations = new HashMap<>();
    private final Clock clock;

    public InMemoryReservationRepository(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    @Override public synchronized CompletionStage<ReservationSnapshot> reserve(CrewReservation request) {
        return result(() -> {
            request.crew().values().forEach(LoadoutCatalog.starter()::validate);
            var existing = reservations.get(request.id());
            if (existing != null) {
                if (!existing.request().equals(request)) throw invalid();
                return existing;
            }
            var now = clock.instant();
            if (!request.expiresAt().isAfter(now) || request.createdAt().isAfter(now)) throw invalid();
            reservations.replaceAll((id, value) -> value.active() && value.admissionAttempts().isEmpty()
                    && !value.request().expiresAt().isAfter(now)
                    && !Collections.disjoint(value.request().crew().keySet(), request.crew().keySet())
                    ? inactive(value) : value);
            if (reservations.values().stream().anyMatch(value -> value.active()
                    && !Collections.disjoint(value.request().crew().keySet(), request.crew().keySet()))) throw invalid();
            return put(new ReservationSnapshot(request, Optional.empty(), Map.of(), true));
        });
    }

    @Override public synchronized CompletionStage<Optional<ReservationSnapshot>> find(UUID id) {
        return CompletableFuture.completedFuture(Optional.ofNullable(reservations.get(id)));
    }

    @Override public synchronized CompletionStage<ReservationSnapshot> assign(UUID id, BackendAssignment target) {
        return result(() -> {
            Objects.requireNonNull(target);
            var value = current(id);
            if (value.assignment().isPresent() && !value.assignment().get().equals(target)) throw invalid();
            if (reservations.values().stream().anyMatch(other -> other.active() && !other.request().id().equals(id)
                    && other.assignment().filter(bound -> bound.serverId().equals(target.serverId())
                    && bound.incarnation().equals(target.incarnation()) && bound.matchId().equals(target.matchId())).isPresent())) throw invalid();
            return put(new ReservationSnapshot(value.request(), Optional.of(target), value.admissionAttempts(), true));
        });
    }

    @Override public synchronized CompletionStage<ReservationSnapshot> admit(UUID id, UUID playerId,
            BackendAssignment target, UUID attemptId) {
        return result(() -> {
            Objects.requireNonNull(attemptId);
            var value = current(id);
            if (!value.assignment().equals(Optional.of(target)) || !value.request().crew().containsKey(playerId)) throw invalid();
            var previous = value.admissionAttempts().get(playerId);
            if (previous != null && !previous.equals(attemptId)) throw invalid();
            var attempts = new HashMap<>(value.admissionAttempts());
            attempts.put(playerId, attemptId);
            return put(new ReservationSnapshot(value.request(), value.assignment(), attempts, true));
        });
    }

    @Override public synchronized CompletionStage<ReservationSnapshot> release(UUID id, BackendAssignment target) {
        return result(() -> {
            var value = require(id);
            if (!value.assignment().equals(Optional.of(target))) throw invalid();
            return put(inactive(value));
        });
    }

    @Override public synchronized CompletionStage<ReservationSnapshot> cancelUnclaimed(UUID id) {
        return result(() -> {
            var value = require(id);
            if (!value.admissionAttempts().isEmpty()) throw invalid();
            return put(inactive(value));
        });
    }

    private ReservationSnapshot current(UUID id) {
        var value = require(id);
        if (!value.active() || !value.request().expiresAt().isAfter(clock.instant())) throw invalid();
        return value;
    }
    private ReservationSnapshot require(UUID id) {
        var value = reservations.get(id);
        if (value == null) throw invalid();
        return value;
    }
    private ReservationSnapshot put(ReservationSnapshot value) {
        reservations.put(value.request().id(), value);
        return value;
    }
    private static ReservationSnapshot inactive(ReservationSnapshot value) {
        return new ReservationSnapshot(value.request(), value.assignment(), value.admissionAttempts(), false);
    }
    private static IllegalStateException invalid() {
        return new IllegalStateException("Reservation conflicts, is stale, expired, or already consumed");
    }
    private static <T> CompletionStage<T> result(Supplier<T> operation) {
        try { return CompletableFuture.completedFuture(operation.get()); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
}
