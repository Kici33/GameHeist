package dev.gameheist.runtime;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.network.*;
import dev.gameheist.domain.player.*;
import dev.gameheist.runtime.persistence.InMemoryReservationRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReservationsTest {
    private final MutableClock clock = new MutableClock();
    private final InMemoryReservationRepository repository = new InMemoryReservationRepository(clock);
    private final UUID player = UUID.randomUUID();
    private final BackendAssignment target = new BackendAssignment("backend", UUID.randomUUID(), UUID.randomUUID(), 1);

    @Test void retriesPreserveIdentityAndRejectReplayedClaims() {
        var request = request(player);
        assertEquals(await(repository.reserve(request)), await(repository.reserve(request)));
        await(repository.assign(request.id(), target));
        var attempt = UUID.randomUUID();
        var accepted = await(repository.admit(request.id(), player, target, attempt));
        assertTrue(accepted.wholeCrewAdmitted());
        assertEquals(accepted, await(repository.admit(request.id(), player, target, attempt)));
        assertThrows(CompletionException.class, () -> await(repository.admit(request.id(), player, target, UUID.randomUUID())));
        assertThrows(CompletionException.class, () -> await(repository.admit(request.id(), UUID.randomUUID(), target, attempt)));
        var stale = new BackendAssignment(target.serverId(), UUID.randomUUID(), target.matchId(), 1);
        assertThrows(CompletionException.class, () -> await(repository.release(request.id(), stale)));
        assertTrue(await(repository.find(request.id())).orElseThrow().active());
    }

    @Test void abandonedUnclaimedCrewCanBeReplacedAtDeadline() {
        var original = request(player);
        await(repository.reserve(original));
        await(repository.assign(original.id(), target));
        clock.now = original.expiresAt();
        assertThrows(CompletionException.class, () -> await(repository.admit(original.id(), player, target, UUID.randomUUID())));
        var replacement = request(player);
        await(repository.reserve(replacement));
        await(repository.assign(replacement.id(), target));
        assertFalse(await(repository.reserve(original)).active());
    }

    @Test void partialTransferRetainsCrewUntilBackendReleases() {
        var original = request(player, UUID.randomUUID());
        await(repository.reserve(original));
        await(repository.assign(original.id(), target));
        await(repository.admit(original.id(), player, target, UUID.randomUUID()));
        clock.now = original.expiresAt();
        assertThrows(CompletionException.class, () -> await(repository.reserve(request(player))));
        assertThrows(CompletionException.class, () -> await(repository.cancelUnclaimed(original.id())));
        var released = await(repository.release(original.id(), target));
        assertFalse(released.active());
        assertEquals(released, await(repository.release(original.id(), target)));
        assertTrue(await(repository.reserve(request(player))).active());
    }

    @Test void backendMatchCannotBeAssignedToTwoCrewsEvenAcrossGenerations() {
        var first = request(player);
        var second = request(UUID.randomUUID());
        await(repository.reserve(first));
        await(repository.reserve(second));
        await(repository.assign(first.id(), target));
        var next = new BackendAssignment(target.serverId(), target.incarnation(), target.matchId(), 2);
        assertThrows(CompletionException.class, () -> await(repository.assign(second.id(), next)));
        await(repository.cancelUnclaimed(first.id()));
        assertEquals(Optional.of(next), await(repository.assign(second.id(), next)).assignment());
    }

    @Test void concurrentCrewsCannotAcquireTheSamePlayer() {
        var contenders = java.util.stream.IntStream.range(0, 16).mapToObj(index -> request(player)).toList();
        var futures = contenders.stream().map(request -> CompletableFuture.supplyAsync(() -> {
            try { await(repository.reserve(request)); return 1; }
            catch (CompletionException expected) { return 0; }
        })).toList();
        assertEquals(1, futures.stream().mapToInt(CompletableFuture::join).sum());
    }

    private CrewReservation request(UUID... players) {
        Map<UUID, Loadout> crew = new HashMap<>();
        for (var id : players) crew.put(id, Loadout.starter(Role.SCOUT));
        return new CrewReservation(UUID.randomUUID(), new ArenaKey("graybox", 3), Difficulty.NORMAL,
                crew, clock.instant(), clock.instant().plusSeconds(60));
    }
    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-14T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
