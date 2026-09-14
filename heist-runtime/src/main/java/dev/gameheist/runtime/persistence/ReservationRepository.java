package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.network.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Trusted coordinator/backend boundary, not a client-facing authorization API. */
public interface ReservationRepository {
    CompletionStage<ReservationSnapshot> reserve(CrewReservation request);
    CompletionStage<Optional<ReservationSnapshot>> find(UUID reservationId);
    CompletionStage<ReservationSnapshot> assign(UUID reservationId, BackendAssignment target);
    /** Reuse attemptId only for an ambiguous retry of the same admission operation. */
    CompletionStage<ReservationSnapshot> admit(UUID reservationId, UUID playerId, BackendAssignment target, UUID attemptId);
    /** Only after the assigned backend has finalized its result and cleaned up its match. */
    CompletionStage<ReservationSnapshot> release(UUID reservationId, BackendAssignment target);
    /** Safe only before any admission was consumed. */
    CompletionStage<ReservationSnapshot> cancelUnclaimed(UUID reservationId);
}
