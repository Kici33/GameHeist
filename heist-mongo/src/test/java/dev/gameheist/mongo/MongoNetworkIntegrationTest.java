package dev.gameheist.mongo;

import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Updates;
import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.network.*;
import dev.gameheist.domain.player.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated native replica set; no application database or environment URI is used. */
@EnabledIfEnvironmentVariable(named = "HEIST_REWARD_TESTS", matches = "true")
class MongoNetworkIntegrationTest {
    private static final String URI = "mongodb://127.0.0.1:27029/?directConnection=true";
    private final String database = "heist_network_test_" + UUID.randomUUID().toString().replace("-", "");
    private final ArenaKey arena = new ArenaKey("graybox", 4);
    private MongoStore store;
    @BeforeAll static void replica() throws Exception { MongoRewardsIntegrationTest.initializeDisposableReplicaSet(); }
    @BeforeEach void open() { store = new MongoStore(URI, database); }
    @AfterEach void close() {
        store.close();
        try (var client = MongoClients.create(URI)) { client.getDatabase(database).drop(); }
    }
    @Test void competingBackendsAllocateOneCrewAndReadinessGatesExactRouting() throws Exception {
        var request = request(UUID.randomUUID()); await(store.reservations().reserve(request));
        var a = backend("heist-a"); var b = backend("heist-b");
        var first = store.network().allocate(a, arena, Difficulty.NORMAL);
        var second = store.network().allocate(b, arena, Difficulty.NORMAL);
        var allocated = List.of(await(first), await(second)).stream().flatMap(Optional::stream).toList();
        assertEquals(1, allocated.size());
        var winner = allocated.getFirst().assignment().orElseThrow();
        var player = request.crew().keySet().iterator().next();
        assertFalse(await(store.network().route(player)).orElseThrow().ready());
        assertThrows(ExecutionException.class, () -> await(store.network().ready(request.id(), winner.equals(a) ? b : a)));
        await(store.network().ready(request.id(), winner));
        var route = await(store.network().route(player)).orElseThrow();
        assertTrue(route.ready()); assertEquals(Optional.of(winner), route.reservation().assignment());
        assertEquals(request.id(), await(store.network().allocate(winner, arena, Difficulty.NORMAL)).orElseThrow().request().id());
    }
    @Test void backendCapacityIsFencedAcrossConcurrentMatchIds() throws Exception {
        var a = backend("heist-a");
        await(store.reservations().reserve(request(UUID.randomUUID())));
        await(store.reservations().reserve(request(UUID.randomUUID())));
        var b = new BackendAssignment(a.serverId(), a.incarnation(), UUID.randomUUID(), 2);
        var first = store.network().allocate(a, arena, Difficulty.NORMAL).toCompletableFuture();
        var second = store.network().allocate(b, arena, Difficulty.NORMAL).toCompletableFuture();
        int successes = 0;
        for (var operation : List.of(first, second)) {
            try { assertTrue(await(operation).isPresent()); successes++; }
            catch (ExecutionException expected) { assertInstanceOf(IllegalStateException.class, expected.getCause()); }
        }
        assertEquals(1, successes);
    }
    @Test void offersMatchArenaAndDifficultyAndOldestCompatibleCrewWins() throws Exception {
        var backend = backend("heist-a");
        var first = request(UUID.randomUUID()); await(store.reservations().reserve(first));
        var second = request(UUID.randomUUID()); await(store.reservations().reserve(second));
        assertTrue(await(store.network().allocate(backend, arena, Difficulty.HARD)).isEmpty());
        assertTrue(await(store.network().allocate(backend, new ArenaKey("graybox", 3), Difficulty.NORMAL)).isEmpty());
        var expected = List.of(first, second).stream().min(Comparator.comparing(CrewReservation::createdAt).thenComparing(r -> r.id().toString())).orElseThrow();
        assertEquals(expected.id(), await(store.network().allocate(backend, arena, Difficulty.NORMAL)).orElseThrow().request().id());
    }
    @Test void takeoverRejectsOldGenerationAndRecoversPartialTransfersOnlyAfterLeaseLoss() throws Exception {
        var first = UUID.randomUUID(); var second = UUID.randomUUID();
        var request = request(first, second); await(store.reservations().reserve(request));
        var target = backend("heist-a"); await(store.network().allocate(target, arena, Difficulty.NORMAL));
        await(store.network().ready(request.id(), target));
        var attempt = UUID.randomUUID(); await(store.reservations().admit(request.id(), first, target, attempt));
        assertEquals(attempt, await(store.reservations().admit(request.id(), first, target, attempt)).admissionAttempts().get(first));
        assertThrows(ExecutionException.class, () -> await(store.reservations().admit(request.id(), first, target, UUID.randomUUID())));
        assertThrows(ExecutionException.class, () -> await(store.reservations().cancelUnclaimed(request.id())));
        assertFalse(await(store.network().recover(request.id())));
        var newIncarnation = UUID.randomUUID();
        assertThrows(ExecutionException.class, () -> await(store.network().heartbeat(target.serverId(), newIncarnation, "backend")));
        expire(target.serverId());
        assertFalse(await(store.network().route(second)).orElseThrow().ready());
        await(store.network().heartbeat(target.serverId(), newIncarnation, "backend"));
        assertThrows(ExecutionException.class, () -> await(store.network().heartbeat(target.serverId(), target.incarnation(), "backend")));
        assertThrows(ExecutionException.class, () -> await(store.network().allocate(target, arena, Difficulty.NORMAL)));
        assertTrue(await(store.network().recover(request.id())));
        assertTrue(await(store.network().route(first)).isEmpty());
        assertThrows(ExecutionException.class, () -> await(store.reservations().admit(request.id(), second, target, UUID.randomUUID())));
        assertEquals(0, await(store.rewards().progression(first)).experience());
    }
    @Test void cancelledAndExpiredQueuesReleaseMembersAndLobbyLookupIgnoresDeadProcesses() throws Exception {
        UUID player = UUID.randomUUID(); var request = request(player);
        await(store.reservations().reserve(request));
        assertFalse(await(store.network().recover(request.id())));
        await(store.reservations().cancelUnclaimed(request.id()));
        assertTrue(await(store.network().route(player)).isEmpty());
        var expired = request(player); await(store.reservations().reserve(expired));
        try (var client = MongoClients.create(URI)) {
            var now = Instant.now();
            client.getDatabase(database).getCollection("reservations").updateOne(eq("_id", expired.id().toString()),
                    Updates.combine(Updates.set("createdAt", Date.from(now.minusSeconds(121))), Updates.set("expiresAt", Date.from(now.minusSeconds(1)))));
        }
        assertTrue(await(store.network().recover(expired.id())));
        assertTrue(await(store.network().route(player)).isEmpty());
        await(store.network().heartbeat("lobby-a", UUID.randomUUID(), "lobby"));
        await(store.network().heartbeat("lobby-b", UUID.randomUUID(), "lobby"));
        assertEquals(Optional.of("lobby-a"), await(store.network().lobby()));
        assertEquals(Optional.of("lobby-b"), await(store.network().lobby("lobby-a")));
        expire("lobby-a"); assertEquals(Optional.of("lobby-b"), await(store.network().lobby()));
        expire("lobby-b"); assertTrue(await(store.network().lobby()).isEmpty());
    }
    @Test void newProfileSessionFencesStaleAndLegacyWritersEvenAtTheSameRevision() throws Exception {
        UUID player = UUID.randomUUID();
        var lobby = store.sessionProfiles(); var backend = store.sessionProfiles();
        var original = await(lobby.loadOrCreate(player));
        assertEquals(original, await(backend.loadOrCreate(player)));
        var changed = new PlayerProfile(player, 1, original.presets(), 0, original.settings());
        assertThrows(ExecutionException.class, () -> await(lobby.save(changed, 0)));
        assertThrows(ExecutionException.class, () -> await(store.save(changed, 0)));
        await(backend.save(changed, 0)); await(backend.save(changed, 0));
        backend.closed(player);
        var next = new PlayerProfile(player, 2, original.presets(), 0, original.settings());
        assertThrows(ExecutionException.class, () -> await(backend.save(next, 1)));
        assertEquals(changed, await(lobby.loadOrCreate(player)));
        await(lobby.save(next, 1));
    }
    private BackendAssignment backend(String name) throws Exception {
        var target = new BackendAssignment(name, UUID.randomUUID(), UUID.randomUUID(), 1);
        await(store.network().heartbeat(name, target.incarnation(), "backend")); return target;
    }
    private CrewReservation request(UUID... players) {
        Map<UUID, Loadout> crew = new HashMap<>();
        for (var player : players) crew.put(player, Loadout.starter(Role.TECHNICIAN));
        var now = Instant.ofEpochMilli(System.currentTimeMillis());
        return new CrewReservation(UUID.randomUUID(), arena, Difficulty.NORMAL, crew, now, now.plusSeconds(120));
    }
    private void expire(String name) {
        try (var client = MongoClients.create(URI)) {
            client.getDatabase(database).getCollection("network_servers").updateOne(eq("_id", name), Updates.set("leaseUntil", new Date(0)));
        }
    }
    private static <T> T await(CompletionStage<T> operation) throws Exception { return operation.toCompletableFuture().get(15, TimeUnit.SECONDS); }
}
