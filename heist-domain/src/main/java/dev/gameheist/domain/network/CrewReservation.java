package dev.gameheist.domain.network;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.player.*;
import java.time.*;
import java.util.*;

/** Immutable request identity, including the lobby-validated loadout snapshots. */
public record CrewReservation(UUID id, ArenaKey arena, Difficulty difficulty, Map<UUID, Loadout> crew,
                             Instant createdAt, Instant expiresAt) {
    public CrewReservation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(arena);
        Objects.requireNonNull(difficulty);
        crew = Map.copyOf(crew);
        if (crew.isEmpty() || crew.size() > 4) throw new IllegalArgumentException("Reservation requires 1-4 players");
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(expiresAt);
        var lifetime = Duration.between(createdAt, expiresAt);
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Admission lifetime must be positive and at most two minutes");
        }
        // BSON dates have millisecond precision; avoid silently changing idempotency identity.
        if (createdAt.getNano() % 1_000_000 != 0 || expiresAt.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("Reservation timestamps require millisecond precision");
        }
    }
}
