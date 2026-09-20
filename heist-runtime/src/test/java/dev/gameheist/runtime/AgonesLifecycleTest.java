package dev.gameheist.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.gameheist.runtime.agones.AgonesLifecycle;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgonesLifecycleTest {
    @Test void readinessAllocationHealthAndShutdownFollowObservedServerState() throws Exception {
        var state = new AtomicReference<>("Starting");
        var ready = new AtomicInteger(); var health = new AtomicInteger(); var shutdown = new AtomicInteger();
        var server = fixture(state, ready, health, shutdown);
        try (var lifecycle = new AgonesLifecycle(origin(server), ignored -> {})) {
            poll(lifecycle, true, false, false, () -> ready.get() == 1);
            assertFalse(lifecycle.allocated());
            state.set("Ready"); poll(lifecycle, true, false, false, () -> lifecycle.state() == AgonesLifecycle.State.READY);
            state.set("Allocated"); poll(lifecycle, true, false, false, lifecycle::allocated);
            assertTrue(health.get() > 0);
            poll(lifecycle, false, true, true, () -> shutdown.get() == 1 && lifecycle.state() == AgonesLifecycle.State.SHUTDOWN);
            assertEquals(AgonesLifecycle.State.SHUTDOWN, lifecycle.state());
        } finally { server.stop(0); }
    }
    @Test void neverRequestsShutdownBeforeDrainBarrierIsSafe() throws Exception {
        var state = new AtomicReference<>("Allocated"); var shutdown = new AtomicInteger();
        var server = fixture(state, new AtomicInteger(), new AtomicInteger(), shutdown);
        try (var lifecycle = new AgonesLifecycle(origin(server), ignored -> {})) {
            for (int i = 0; i < 30; i++) { lifecycle.tick(false, true, false); Thread.sleep(5); }
            assertEquals(0, shutdown.get());
            poll(lifecycle, false, true, true, () -> shutdown.get() == 1);
        } finally { server.stop(0); }
    }
    @Test void rejectsRemoteOrCredentialedSdkOrigins() {
        assertThrows(IllegalArgumentException.class, () -> new AgonesLifecycle(URI.create("http://example.com:9358"), ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> new AgonesLifecycle(URI.create("http://user@127.0.0.1:9358"), ignored -> {}));
    }
    private static HttpServer fixture(AtomicReference<String> state, AtomicInteger ready, AtomicInteger health, AtomicInteger shutdown) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/ready", e -> reply(e, ready, "{}"));
        server.createContext("/health", e -> reply(e, health, "{}"));
        server.createContext("/shutdown", e -> reply(e, shutdown, "{}"));
        server.createContext("/gameserver", e -> reply(e, null, "{\"status\":{\"state\":\"" + state.get() + "\"}}"));
        server.start(); return server;
    }
    private static void reply(com.sun.net.httpserver.HttpExchange exchange, AtomicInteger counter, String body) throws java.io.IOException {
        try (exchange) {
            if (counter != null) counter.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        }
    }
    private static URI origin(HttpServer server) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    private static void poll(AgonesLifecycle lifecycle, boolean ready, boolean draining, boolean safe, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { lifecycle.tick(ready, draining, safe); Thread.sleep(10); }
        assertTrue(condition.getAsBoolean());
    }
}
