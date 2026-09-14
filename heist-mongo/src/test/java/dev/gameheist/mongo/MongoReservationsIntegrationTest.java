package dev.gameheist.mongo;

import com.mongodb.client.MongoClients;
import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.network.*;
import dev.gameheist.domain.player.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "HEIST_MONGO_TESTS", matches = "true")
class MongoReservationsIntegrationTest {
    private static final String URI = "mongodb://127.0.0.1:27028/?directConnection=true";
    private final String database = "heist_test_" + UUID.randomUUID().toString().replace("-", "");
    private MongoStore store;
    @BeforeEach void open() { store = new MongoStore(URI, database); }
    @AfterEach void close() {
        store.close();
        try (var client = MongoClients.create(URI)) { client.getDatabase(database).drop(); }
    }
    @Test void overlappingCrewsRaceAtomicallyAcrossCoordinators() throws Exception {
        UUID shared = UUID.randomUUID();
        var first = request(shared, UUID.randomUUID());
        var second = request(shared, UUID.randomUUID());
        try (var other = new MongoStore(URI, database)) {
            var a = store.reservations().reserve(first).toCompletableFuture();
            var b = other.reservations().reserve(second).toCompletableFuture();
            await(a.handle((v, e) -> null));
            await(b.handle((v, e) -> null));
            assertNotEquals(a.isCompletedExceptionally(), b.isCompletedExceptionally());
        }
    }
    @Test void requestRetryIsIdempotentButPayloadCannotChange() throws Exception {
        var request = request(UUID.randomUUID());
        var original = await(store.reservations().reserve(request));
        assertEquals(original, await(store.reservations().reserve(request)));
        var changed = new CrewReservation(request.id(), request.arena(), Difficulty.HARD, request.crew(), request.createdAt(), request.expiresAt());
        assertThrows(ExecutionException.class, () -> await(store.reservations().reserve(changed)));
        await(store.reservations().cancelUnclaimed(request.id()));
        assertFalse(await(store.reservations().reserve(request)).active(), "Old request IDs never reactivate");
    }
    @Test void assignmentPinsProcessAndMatchAndPreventsTwoCrewsSharingMatch() throws Exception {
        var first = request(UUID.randomUUID());
        var second = request(UUID.randomUUID());
        await(store.reservations().reserve(first));
        await(store.reservations().reserve(second));
        var target = target();
        var assigned = await(store.reservations().assign(first.id(), target));
        assertEquals(assigned, await(store.reservations().assign(first.id(), target)));
        assertThrows(ExecutionException.class, () -> await(store.reservations().assign(first.id(), target())));
        assertThrows(ExecutionException.class, () -> await(store.reservations().assign(second.id(), target)));
    }
    @Test void consumesOnceAndOnlyAllowsSameAttemptRetry() throws Exception {
        UUID player = UUID.randomUUID(), attempt = UUID.randomUUID();
        var request = request(player);
        var target = target();
        await(store.reservations().reserve(request));
        await(store.reservations().assign(request.id(), target));
        assertThrows(ExecutionException.class, () -> await(store.reservations().admit(request.id(), UUID.randomUUID(), target, attempt)));
        var stale = new BackendAssignment(target.serverId(), UUID.randomUUID(), target.matchId(), target.generation());
        assertThrows(ExecutionException.class, () -> await(store.reservations().admit(request.id(), player, stale, attempt)));
        var obsolete = new BackendAssignment(target.serverId(), target.incarnation(), target.matchId(), target.generation() + 1);
        assertThrows(ExecutionException.class, () -> await(store.reservations().admit(request.id(), player, obsolete, attempt)));
        var admitted = await(store.reservations().admit(request.id(), player, target, attempt));
        assertTrue(admitted.wholeCrewAdmitted());
        assertEquals(admitted, await(store.reservations().admit(request.id(), player, target, attempt)));
        assertThrows(ExecutionException.class, () -> await(store.reservations().admit(request.id(), player, target, UUID.randomUUID())));
    }
    @Test void expiredUnclaimedReservationCanBeReplacedWithoutTtlDeletion() throws Exception {
        UUID player = UUID.randomUUID();
        var old = request(player);
        await(store.reservations().reserve(old));
        expire(old);
        assertThrows(ExecutionException.class, () -> await(store.reservations().assign(old.id(), target())));
        assertTrue(await(store.reservations().reserve(request(player))).active());
        assertFalse(await(store.reservations().find(old.id())).orElseThrow().active());
    }
    @Test void expiredPartialTransferRetainsCrewUntilExactBackendReleasesIt() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var request = request(first, second);
        var target = target();
        await(store.reservations().reserve(request));
        await(store.reservations().assign(request.id(), target));
        await(store.reservations().admit(request.id(), first, target, UUID.randomUUID()));
        expire(request);
        assertThrows(ExecutionException.class, () -> await(store.reservations().admit(request.id(), second, target, UUID.randomUUID())));
        assertThrows(ExecutionException.class, () -> await(store.reservations().reserve(request(second))));
        assertThrows(ExecutionException.class, () -> await(store.reservations().cancelUnclaimed(request.id())));
        assertThrows(ExecutionException.class, () -> await(store.reservations().release(request.id(), target())));
        assertFalse(await(store.reservations().release(request.id(), target)).active());
        assertFalse(await(store.reservations().release(request.id(), target)).active());
        assertTrue(await(store.reservations().reserve(request(second))).active());
    }
    @Test void reservationsAndCapturedLoadoutsSurviveNewClients() throws Exception {
        var request = request(UUID.randomUUID());
        var target = target();
        await(store.reservations().reserve(request));
        var assigned = await(store.reservations().assign(request.id(), target));
        try (var other = new MongoStore(URI, database)) {
            assertEquals(assigned, await(other.reservations().find(request.id())).orElseThrow());
        }
    }
    @Test void fullBackendLifecyclePersistsResultBeforeReleasingCrew() throws Exception {
        var key = new ArenaKey("bank", 1);
        var registry = new dev.gameheist.runtime.arena.ArenaRegistry();
        registry.register(new dev.gameheist.domain.arena.ArenaDefinition(key, "Bank",
                new dev.gameheist.domain.arena.Bounds(new dev.gameheist.domain.arena.Position(-32, 64, -32, 0, 0),
                        new dev.gameheist.domain.arena.Position(32, 96, 32, 0, 0)),
                new dev.gameheist.domain.arena.Position(0, 65, 0, 0, 0), 4, Duration.ofMinutes(20), List.of(
                new dev.gameheist.domain.objective.ObjectiveDefinition("security", dev.gameheist.domain.objective.ObjectiveType.INTERACT,
                        dev.gameheist.domain.match.MatchPhase.INFILTRATION, Set.of(), true),
                new dev.gameheist.domain.objective.ObjectiveDefinition("vault", dev.gameheist.domain.objective.ObjectiveType.DRILL,
                        dev.gameheist.domain.match.MatchPhase.VAULT, Set.of("security"), true),
                new dev.gameheist.domain.objective.ObjectiveDefinition("escape", dev.gameheist.domain.objective.ObjectiveType.EXTRACT,
                        dev.gameheist.domain.match.MatchPhase.EXTRACTION, Set.of("vault"), true))));
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        var manager = new dev.gameheist.runtime.instance.InstanceManager(registry, (id, arena) ->
                new dev.gameheist.runtime.instance.WorldInstance() {
                    @Override public String name() { return id.toString(); }
                    @Override public void release() { released.set(true); }
                }, store, Clock.systemUTC(), LoadoutCatalog.starter(), 1);
        UUID player = UUID.randomUUID();
        var initial = request(player);
        var request = new CrewReservation(initial.id(), key, initial.difficulty(), initial.crew(), initial.createdAt(), initial.expiresAt());
        await(store.reservations().reserve(request));
        UUID match = manager.createPractice(key, Difficulty.NORMAL, 42);
        var target = new BackendAssignment("game-1", UUID.randomUUID(), match, 1);
        manager.bindReservation(await(store.reservations().assign(request.id(), target)), target, store.reservations());
        UUID attempt = UUID.randomUUID();
        manager.joinReserved(player, attempt, await(store.reservations().admit(request.id(), player, target, attempt)));
        manager.start(match);
        for (String objective : List.of("security", "vault", "escape")) manager.completeObjective(match, objective);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!manager.all().isEmpty() && System.nanoTime() < deadline) { manager.tick(); Thread.sleep(5); }
        assertTrue(manager.all().isEmpty());
        assertTrue(released.get());
        assertFalse(await(store.reservations().find(request.id())).orElseThrow().active());
        try (var client = MongoClients.create(URI)) {
            assertEquals(1, client.getDatabase(database).getCollection("match_results").countDocuments());
        }
        assertTrue(await(store.reservations().reserve(request(player))).active());
    }
    private void expire(CrewReservation request) {
        try (var client = MongoClients.create(URI)) {
            client.getDatabase(database).getCollection("reservations").updateOne(new Document("_id", request.id().toString()),
                    new Document("$set", new Document("expiresAt", Date.from(request.createdAt().plusMillis(1)))));
        }
    }
    private static CrewReservation request(UUID... players) {
        Map<UUID, Loadout> crew = new HashMap<>();
        for (UUID player : players) crew.put(player, Loadout.starter(Role.SCOUT));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new CrewReservation(UUID.randomUUID(), new ArenaKey("graybox", 3), Difficulty.NORMAL, crew, now.minusSeconds(60), now.plusSeconds(60));
    }
    private static BackendAssignment target() { return new BackendAssignment("heist-1", UUID.randomUUID(), UUID.randomUUID(), 1); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(10, TimeUnit.SECONDS); }
}
