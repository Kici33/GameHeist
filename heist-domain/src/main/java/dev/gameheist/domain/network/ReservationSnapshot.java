package dev.gameheist.domain.network;

import java.util.*;

public record ReservationSnapshot(CrewReservation request, Optional<BackendAssignment> assignment,
                                  Map<UUID, UUID> admissionAttempts, boolean active) {
    public ReservationSnapshot {
        Objects.requireNonNull(request);
        Objects.requireNonNull(assignment);
        admissionAttempts = Map.copyOf(admissionAttempts);
        if (!request.crew().keySet().containsAll(admissionAttempts.keySet())
                || (assignment.isEmpty() && !admissionAttempts.isEmpty())) {
            throw new IllegalArgumentException("Invalid reservation admission roster");
        }
    }
    public boolean wholeCrewAdmitted() { return admissionAttempts.size() == request.crew().size(); }
}
