package dev.gameheist.mongo;

import com.mongodb.client.MongoClients;
import dev.gameheist.domain.player.*;
import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.objective.*;
import dev.gameheist.runtime.arena.ArenaRegistry;
import dev.gameheist.runtime.instance.*;
import dev.gameheist.runtime.persistence.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "HEIST_MONGO_TESTS", matches = "true")
class MongoFailureIntegrationTest {
    private static final String URI = "mongodb://127.0.0.1:27028/?directConnection=true";
    private final String database = "heist_failure_test_" + UUID.randomUUID().toString().replace("-", "");
    @AfterEach void clean() { try (var client = MongoClients.create(URI)) { client.getDatabase(database).drop(); } }

    @Test void severedDatabaseConnectionsFailAdmissionThenSameClientRecovers() throws Exception {
        try (var proxy = new WireGate(); var store = new MongoStore(proxy.uri(), database)) {
            UUID id = UUID.randomUUID();
            var original = await(store.loadOrCreate(id));
            proxy.online(false);
            var sessions = new ProfileSessions(store, LoadoutCatalog.starter());
            sessions.open(id);
            assertThrows(IllegalStateException.class, () -> sessions.require(id));
            poll(() -> { sessions.tick(); return sessions.status(id) == ProfileSessions.Status.FAILED; });
            assertThrows(IllegalStateException.class, () -> sessions.require(id));
            proxy.online(true);
            sessions.reload(id);
            poll(() -> { sessions.tick(); return sessions.status(id) != ProfileSessions.Status.LOADING; });
            assertEquals(original, sessions.require(id));
        }
    }
    @Test void acknowledgedDatabaseWriteWithLostReplyReconcilesAfterReconnect() throws Exception {
        try (var store = new MongoStore(URI, database)) {
            var diskSaved = new CompletableFuture<Void>();
            var releaseReply = new CompletableFuture<Void>();
            ProfileRepository lostReply = new ProfileRepository() {
                public CompletionStage<PlayerProfile> loadOrCreate(UUID id) { return store.loadOrCreate(id); }
                public CompletionStage<Void> save(PlayerProfile profile, long revision) {
                    return store.save(profile, revision).thenCompose(ignored -> {
                        diskSaved.complete(null);
                        return releaseReply.thenCompose(v -> CompletableFuture.failedFuture(new IOException("Injected lost acknowledgement")));
                    });
                }
            };
            UUID id = UUID.randomUUID();
            var sessions = new ProfileSessions(lostReply, LoadoutCatalog.starter());
            sessions.open(id); poll(() -> { sessions.tick(); return sessions.status(id) == ProfileSessions.Status.READY; });
            sessions.edit(id, p -> new PlayerProfile(id, 1, List.of(Loadout.starter(Role.SCOUT)), 0, p.settings()));
            await(diskSaved); sessions.close(id); sessions.open(id);
            assertThrows(IllegalStateException.class, () -> sessions.require(id));
            assertEquals(1, sessions.unacknowledgedWrites());
            releaseReply.complete(null);
            poll(() -> { sessions.tick(); return sessions.status(id) == ProfileSessions.Status.READY; });
            assertEquals(1, sessions.require(id).revision());
            assertEquals(Role.SCOUT, sessions.require(id).selectedLoadout().role());
            assertEquals(0, sessions.unacknowledgedWrites());
            sessions.drain(); assertEquals(0, sessions.pendingOperations());
        }
    }
    @Test void saturatedStorageQueueRejectsImmediatelyAndAcceptedWorkDrains() throws Exception {
        var store = new MongoStore(URI, database);
        try {
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            List<CompletableFuture<Integer>> accepted = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) accepted.add(store.submit(() -> {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timeout"); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    return 1;
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (int i = 0; i < 128; i++) accepted.add(store.submit(() -> 1));
                var refused = store.loadOrCreate(UUID.randomUUID()).toCompletableFuture();
                assertTrue(refused.isCompletedExceptionally(), "Overload must not block the caller");
                assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class, () -> await(refused)).getCause());
                store.close();
                assertTrue(store.loadOrCreate(UUID.randomUUID()).toCompletableFuture().isCompletedExceptionally());
            } finally { release.countDown(); }
            await(CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)));
            assertEquals(130, accepted.stream().mapToInt(CompletableFuture::join).sum());
        } finally { store.close(); }
    }
    @Test void lostResultAcknowledgementKeepsCapacityUntilIdenticalRetryIsAcknowledged() throws Exception {
        try (var store = new MongoStore(URI, database)) {
            var key = new ArenaKey("acceptance", 1);
            var arenas = new ArenaRegistry();
            var origin = new Position(0, 65, 0, 0, 0);
            arenas.register(new ArenaDefinition(key, "Acceptance", new Bounds(origin, new Position(32, 96, 32, 0, 0)),
                    origin, 4, Duration.ofMinutes(20), List.of(
                            new ObjectiveDefinition("security", ObjectiveType.INTERACT, MatchPhase.INFILTRATION, Set.of(), true),
                            new ObjectiveDefinition("vault", ObjectiveType.DRILL, MatchPhase.VAULT, Set.of("security"), true),
                            new ObjectiveDefinition("escape", ObjectiveType.EXTRACT, MatchPhase.EXTRACTION, Set.of("vault"), true))));
            var clock = new TestClock();
            var released = new AtomicInteger();
            List<MatchResult> attempts = new ArrayList<>();
            var lostReply = new CompletableFuture<Void>();
            var committed = new CompletableFuture<Void>();
            ResultRepository repository = result -> {
                attempts.add(result);
                return store.save(result).thenCompose(ignored -> {
                    if (attempts.size() != 1) return CompletableFuture.completedFuture(null);
                    committed.complete(null);
                    return lostReply;
                });
            };
            var manager = new InstanceManager(arenas, (id, arena) -> new WorldInstance() {
                public String name() { return id.toString(); }
                public void release() { released.incrementAndGet(); }
            }, repository, clock, LoadoutCatalog.starter(), 1);
            var id = manager.createPractice(key, Difficulty.NORMAL, 1);
            manager.join(id, UUID.randomUUID(), Loadout.starter(Role.SCOUT));
            manager.stop(id, "acceptance");
            manager.drain();
            await(committed);
            manager.tick();
            assertEquals(InstanceState.FINALIZING, manager.snapshot(id).state());
            assertEquals(0, released.get());
            assertThrows(IllegalStateException.class, () -> manager.createPractice(key, Difficulty.NORMAL, 1));
            lostReply.completeExceptionally(new IOException("Injected lost result acknowledgement"));
            manager.tick();
            clock.now = clock.now.plusSeconds(5);
            poll(() -> { manager.tick(); return manager.all().isEmpty(); });
            assertEquals(2, attempts.size());
            assertSame(attempts.getFirst(), attempts.getLast());
            assertEquals(1, released.get());
            manager.tick();
            assertEquals(1, released.get());
            try (var client = MongoClients.create(URI)) {
                var results = client.getDatabase(database).getCollection("match_results");
                assertEquals(1, results.countDocuments());
                assertEquals(MongoDocuments.result(attempts.getFirst()), results.find().first());
            }
        }
    }
    private static final class TestClock extends Clock {
        private Instant now = Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private static <T> T await(CompletionStage<T> work) throws Exception { return work.toCompletableFuture().get(12, TimeUnit.SECONDS); }
    private static void poll(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for recovery");
    }
    /** A real Mongo wire connection, interrupted without touching any external service process. */
    private static final class WireGate implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private volatile boolean online = true;
        WireGate() throws IOException {
            Thread.ofVirtual().start(() -> {
                while (!listener.isClosed()) try {
                    var incoming = listener.accept(); sockets.add(incoming);
                    if (!online) { closeSocket(incoming); continue; }
                    var upstream = new Socket("127.0.0.1", 27028); sockets.add(upstream);
                    if (!online) { closeSocket(incoming); closeSocket(upstream); continue; }
                    pump(incoming, upstream); pump(upstream, incoming);
                } catch (IOException ignored) { /* listener close or test outage */ }
            });
        }
        String uri() { return "mongodb://127.0.0.1:" + listener.getLocalPort() + "/?directConnection=true&heartbeatFrequencyMS=500"; }
        void online(boolean value) { online = value; if (!value) for (var socket : List.copyOf(sockets)) closeSocket(socket); }
        private void pump(Socket source, Socket destination) {
            Thread.ofVirtual().start(() -> {
                try { source.getInputStream().transferTo(destination.getOutputStream()); }
                catch (IOException ignored) { /* deliberate interruption */ }
                finally { closeSocket(source); closeSocket(destination); }
            });
        }
        private void closeSocket(Socket socket) { sockets.remove(socket); try { socket.close(); } catch (IOException ignored) {} }
        public void close() throws IOException { online(false); listener.close(); }
    }
}
