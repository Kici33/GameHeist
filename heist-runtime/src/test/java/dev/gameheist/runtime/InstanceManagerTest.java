package dev.gameheist.runtime;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.objective.*;
import dev.gameheist.domain.player.*;
import dev.gameheist.domain.network.*;
import dev.gameheist.runtime.arena.ArenaRegistry;
import dev.gameheist.runtime.instance.*;
import dev.gameheist.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class InstanceManagerTest {
    private final ArenaKey key = new ArenaKey("bank", 1);
    private final MutableClock clock = new MutableClock();
    private final ArenaRegistry arenas = registry();
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger released = new AtomicInteger();
    private final InMemoryResultRepository results = new InMemoryResultRepository(100);
    private final WorldGateway worlds = (id, arena) -> {
        created.incrementAndGet();
        return world(id, released::incrementAndGet);
    };

    private ArenaRegistry registry() {
        var registry = new ArenaRegistry();
        registry.register(new ArenaDefinition(key, "Bank",
                new Bounds(new Position(-32, 64, -32, 0, 0), new Position(32, 96, 32, 0, 0)),
                new Position(0, 65, 0, 0, 0), 4, Duration.ofMinutes(20), List.of(
                new ObjectiveDefinition("security", ObjectiveType.INTERACT, MatchPhase.INFILTRATION, Set.of(), true),
                new ObjectiveDefinition("vault", ObjectiveType.DRILL, MatchPhase.VAULT, Set.of("security"), true),
                new ObjectiveDefinition("escape", ObjectiveType.EXTRACT, MatchPhase.EXTRACTION, Set.of("vault"), true))));
        return registry;
    }
    private InstanceManager manager(WorldGateway gateway, ResultRepository repository, int capacity) {
        return new InstanceManager(arenas, gateway, repository, clock, LoadoutCatalog.starter(), capacity);
    }
    private UUID create(InstanceManager manager) throws IOException { return manager.createPractice(key, Difficulty.NORMAL, 42); }
    private static WorldInstance world(UUID id, ManagedResource release) {
        return new WorldInstance() {
            @Override public String name() { return id.toString(); }
            @Override public void release() throws IOException { release.release(); }
        };
    }

    @Test void instancesHaveDistinctWorldsAndIndependentCrews() throws IOException {
        var manager = manager(worlds, results, 2);
        UUID first = create(manager), second = create(manager), player = UUID.randomUUID();
        manager.join(first, player, Loadout.starter(Role.SCOUT));
        assertNotEquals(manager.snapshot(first).worldName(), manager.snapshot(second).worldName());
        assertTrue(manager.snapshot(second).match().participants().isEmpty());
        assertThrows(IllegalStateException.class, () -> manager.join(second, player, Loadout.starter(Role.SCOUT)));
    }
    @Test void capacityIsCheckedBeforeProvisioning() throws IOException {
        var manager = manager(worlds, results, 1);
        create(manager);
        assertThrows(IllegalStateException.class, () -> create(manager));
        assertEquals(1, created.get());
    }
    @Test void failedProvisioningDoesNotReserveCapacity() throws IOException {
        var attempts = new AtomicInteger();
        var manager = manager((id, arena) -> {
            if (attempts.getAndIncrement() == 0) throw new IOException("creation failed");
            return world(id, () -> {});
        }, results, 1);
        assertThrows(IOException.class, () -> create(manager));
        assertEquals(0, manager.all().size());
        create(manager);
        assertEquals(1, manager.all().size());
    }
    @Test void waitsForResultAcknowledgementBeforeWorldRelease() throws IOException {
        var acknowledgement = new CompletableFuture<Void>();
        var manager = manager(worlds, result -> acknowledgement, 1);
        UUID id = create(manager);
        manager.stop(id, "test");
        assertEquals(InstanceState.FINALIZING, manager.snapshot(id).state());
        assertEquals(0, released.get());
        acknowledgement.complete(null);
        manager.tick();
        assertEquals(1, released.get());
        assertTrue(manager.all().isEmpty());
    }
    @Test void failedPersistenceRetriesSameResultWithoutFreeingSlot() throws IOException {
        List<MatchResult> submissions = new ArrayList<>();
        var manager = manager(worlds, result -> {
            submissions.add(result);
            return submissions.size() == 1 ? CompletableFuture.failedFuture(new IOException("offline"))
                    : CompletableFuture.completedFuture(null);
        }, 1);
        UUID id = create(manager);
        manager.stop(id, "test");
        assertEquals(0, released.get());
        manager.tick();
        assertEquals(1, submissions.size());
        clock.advance(Duration.ofSeconds(5));
        manager.tick();
        assertEquals(2, submissions.size());
        assertSame(submissions.getFirst(), submissions.getLast());
        assertEquals(1, released.get());
    }
    @Test void failedCleanupRetainsSlotAndRetriesWithoutResaving() throws IOException {
        AtomicInteger unloadAttempts = new AtomicInteger(), saves = new AtomicInteger();
        var manager = manager((id, arena) -> world(id, () -> {
            if (unloadAttempts.incrementAndGet() == 1) throw new IOException("busy");
        }), result -> { saves.incrementAndGet(); return CompletableFuture.completedFuture(null); }, 1);
        UUID id = create(manager), player = UUID.randomUUID();
        manager.join(id, player, Loadout.starter(Role.SCOUT));
        manager.stop(id, "test");
        assertEquals(InstanceState.CLOSING, manager.snapshot(id).state());
        assertThrows(IllegalStateException.class, () -> create(manager));
        assertTrue(manager.instanceOf(player).isPresent());
        clock.advance(Duration.ofSeconds(5));
        manager.tick();
        assertEquals(1, saves.get());
        assertEquals(2, unloadAttempts.get());
        assertTrue(manager.instanceOf(player).isEmpty());
        assertTrue(manager.all().isEmpty());
    }
    @Test void drainBlocksAdmissionButAllowsExistingMatchToFinish() throws IOException {
        var manager = manager(worlds, results, 2);
        UUID id = create(manager);
        manager.join(id, UUID.randomUUID(), Loadout.starter(Role.SUPPORT));
        manager.drain();
        assertThrows(IllegalStateException.class, () -> create(manager));
        assertThrows(IllegalStateException.class, () -> manager.join(id, UUID.randomUUID(), Loadout.starter(Role.SCOUT)));
        manager.start(id);
        for (String objective : List.of("security", "vault", "escape")) manager.completeObjective(id, objective);
        manager.tick();
        assertEquals(MatchOutcome.WON, results.all().getFirst().outcome());
        assertTrue(manager.all().isEmpty());
    }
    @Test void shutdownReportsUnacknowledgedResultsButStillReleasesWorld() throws IOException {
        var manager = manager(worlds, result -> new CompletableFuture<>(), 1);
        create(manager);
        var failures = manager.shutdown();
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("not acknowledged"));
        assertEquals(1, released.get());
        assertTrue(manager.shutdown().isEmpty());
    }
    @Test void drainCompletionWaitsForResultAndRetryableCleanup() throws IOException {
        var acknowledgement = new CompletableFuture<Void>();
        var attempts = new AtomicInteger();
        var manager = manager((id, arena) -> world(id, () -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("cleanup pending");
        }), result -> acknowledgement, 1);
        var profiles = new ProfileSessions(new InMemoryProfileRepository(), LoadoutCatalog.starter());
        var drain = new dev.gameheist.runtime.health.DrainController(manager, profiles, () -> true);
        UUID id = create(manager);
        assertFalse(drain.status().safeToStop());
        drain.drain();
        manager.stop(id, "test");
        assertFalse(drain.status().safeToStop());
        acknowledgement.complete(null);
        manager.tick();
        assertEquals(InstanceState.CLOSING, manager.snapshot(id).state());
        assertFalse(drain.status().safeToStop());
        clock.advance(Duration.ofSeconds(5));
        manager.tick();
        assertTrue(drain.status().safeToStop());
        assertThrows(IllegalStateException.class, () -> create(manager));
    }
    @Test void rejectsCallsFromAnotherThread() throws Exception {
        var manager = manager(worlds, results, 1);
        var result = new AtomicReference<Throwable>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try { manager.all(); } catch (Throwable failure) { result.set(failure); }
        });
        thread.join();
        assertInstanceOf(IllegalStateException.class, result.get());
    }
    @Test void scopeTasksReleaseBeforeWorldAndNeverRunTwice() throws IOException {
        List<String> order = new ArrayList<>();
        var manager = manager((id, arena) -> world(id, () -> order.add("world")), results, 1);
        UUID id = create(manager);
        manager.own(id, () -> order.add("first"));
        manager.own(id, () -> order.add("second"));
        manager.stop(id, "test");
        manager.tick();
        assertEquals(List.of("second", "first", "world"), order);
    }
    @Test void publishedArenaVersionCannotBeOverwritten() {
        assertThrows(IllegalArgumentException.class, () -> arenas.register(arenas.require(key)));
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-12T12:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
    @Test void reservedAdmissionRequiresConsumedAttemptFullCrewAndExactTarget() throws IOException {
        var manager = manager(worlds, results, 1);
        UUID id = create(manager), first = UUID.randomUUID(), second = UUID.randomUUID(), attempt = UUID.randomUUID();
        var request = reservation(first, second);
        var target = new BackendAssignment("server-1", UUID.randomUUID(), id, 1);
        var binding = new ReservationSnapshot(request, Optional.of(target), Map.of(), true);
        var repository = new LeaseRepository();
        manager.bindReservation(binding, target, repository);
        assertThrows(IllegalStateException.class, () -> manager.join(id, first, Loadout.starter(Role.TECHNICIAN)));
        assertThrows(IllegalStateException.class, () -> manager.joinReserved(first, attempt, binding));
        var accepted = new ReservationSnapshot(request, Optional.of(target), Map.of(first, attempt), true);
        var stale = new BackendAssignment(target.serverId(), UUID.randomUUID(), id, 1);
        assertThrows(IllegalStateException.class, () -> manager.joinReserved(first, attempt,
                new ReservationSnapshot(request, Optional.of(stale), Map.of(first, attempt), true)));
        manager.joinReserved(first, attempt, accepted);
        assertEquals(Role.SCOUT, manager.snapshot(id).match().participants().get(first).role());
        assertThrows(IllegalStateException.class, () -> manager.joinReserved(first, attempt, accepted));
        assertThrows(IllegalStateException.class, () -> manager.start(id));
        UUID secondAttempt = UUID.randomUUID();
        manager.joinReserved(second, secondAttempt, new ReservationSnapshot(request, Optional.of(target), Map.of(second, secondAttempt), true));
        manager.start(id);
        clock.advance(Duration.ofSeconds(61));
        manager.tick();
        assertEquals(MatchPhase.INFILTRATION, manager.snapshot(id).match().phase(), "Admission expiry does not end active gameplay");
    }
    @Test void reservedCleanupRetainsCapacityUntilDurableReleaseAcknowledgement() throws IOException {
        var manager = manager(worlds, results, 1);
        UUID id = create(manager);
        var request = reservation(UUID.randomUUID());
        var target = new BackendAssignment("server-1", UUID.randomUUID(), id, 1);
        var binding = new ReservationSnapshot(request, Optional.of(target), Map.of(), true);
        var repository = new LeaseRepository();
        manager.bindReservation(binding, target, repository);
        manager.stop(id, "test");
        assertEquals(1, released.get(), "World release precedes membership release");
        assertEquals(1, results.all().size(), "Result saved before lease release");
        assertEquals(1, repository.releases);
        assertEquals(InstanceState.CLOSING, manager.snapshot(id).state());
        repository.release.complete(new ReservationSnapshot(request, Optional.of(target), Map.of(), false));
        manager.tick();
        assertTrue(manager.all().isEmpty());
        assertEquals(1, repository.releases);
    }
    @Test void expiredReservedBriefingAbortsAndRejectsLateAdmission() throws IOException {
        var manager = manager(worlds, results, 1);
        UUID id = create(manager), player = UUID.randomUUID(), attempt = UUID.randomUUID();
        var request = reservation(player);
        var target = new BackendAssignment("server-1", UUID.randomUUID(), id, 1);
        var binding = new ReservationSnapshot(request, Optional.of(target), Map.of(), true);
        var repository = new LeaseRepository();
        manager.bindReservation(binding, target, repository);
        clock.advance(Duration.ofSeconds(60));
        assertThrows(IllegalStateException.class, () -> manager.joinReserved(player, attempt,
                new ReservationSnapshot(request, Optional.of(target), Map.of(player, attempt), true)));
        manager.tick();
        assertEquals("reservation_expired", results.all().getFirst().reason());
        assertEquals(1, repository.releases);
    }
    @Test void mismatchedArenaDifficultyOrOccupiedMatchCannotBeBound() throws IOException {
        var manager = manager(worlds, results, 1);
        UUID id = create(manager), player = UUID.randomUUID();
        var original = reservation(player);
        var target = new BackendAssignment("server-1", UUID.randomUUID(), id, 1);
        var mismatch = new CrewReservation(original.id(), key, Difficulty.HARD, original.crew(), original.createdAt(), original.expiresAt());
        assertThrows(IllegalStateException.class, () -> manager.bindReservation(
                new ReservationSnapshot(mismatch, Optional.of(target), Map.of(), true), target, new LeaseRepository()));
        manager.join(id, player, Loadout.starter(Role.SCOUT));
        assertThrows(IllegalStateException.class, () -> manager.bindReservation(
                new ReservationSnapshot(original, Optional.of(target), Map.of(), true), target, new LeaseRepository()));
    }
    private CrewReservation reservation(UUID... players) {
        Map<UUID, Loadout> crew = new HashMap<>();
        for (UUID player : players) crew.put(player, Loadout.starter(Role.SCOUT));
        return new CrewReservation(UUID.randomUUID(), key, Difficulty.NORMAL, crew, clock.instant(), clock.instant().plusSeconds(60));
    }
    private static final class LeaseRepository implements ReservationRepository {
        private final CompletableFuture<ReservationSnapshot> release = new CompletableFuture<>();
        private int releases;
        @Override public CompletionStage<ReservationSnapshot> release(UUID id, BackendAssignment target) { releases++; return release; }
        @Override public CompletionStage<ReservationSnapshot> reserve(CrewReservation request) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<Optional<ReservationSnapshot>> find(UUID id) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<ReservationSnapshot> assign(UUID id, BackendAssignment target) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<ReservationSnapshot> admit(UUID id, UUID player, BackendAssignment target, UUID attempt) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<ReservationSnapshot> cancelUnclaimed(UUID id) { throw new UnsupportedOperationException(); }
    }
}
