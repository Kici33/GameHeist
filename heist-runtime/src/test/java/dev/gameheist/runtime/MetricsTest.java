package dev.gameheist.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.gameheist.runtime.metrics.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {
    @Test void lineProtocolHasStableTypesOrderAndTimestamp() {
        assertEquals("heist_server,server=local-1 online=3i,tps=19.5 1234\n",
                new MetricsSnapshot(1234, "local-1", Map.of("tps", 19.5, "online", 3L)).lineProtocol());
    }
    @Test void rejectsInjectionNonFiniteValuesAndUnboundedFields() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsSnapshot(1, "bad\nserver", Map.of("online", 1L)));
        assertThrows(IllegalArgumentException.class, () -> new MetricsSnapshot(1, "ok", Map.of("bad,tag", 1L)));
        assertThrows(IllegalArgumentException.class, () -> new MetricsSnapshot(1, "ok", Map.of("tps", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new MetricsSnapshot(1, "ok", Map.of("online", 1)));
        Map<String, Number> fields = new HashMap<>();
        for (int i = 0; i < 65; i++) fields.put("field" + i, 1L);
        assertThrows(IllegalArgumentException.class, () -> new MetricsSnapshot(1, "ok", fields));
    }
    @Test void sendsAuthenticatedRequestAndCountsOnly204AsDelivered() throws Exception {
        var received = new CompletableFuture<String>();
        var headers = new CompletableFuture<String>();
        var path = new CompletableFuture<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/api/v2/write", exchange -> {
            try (exchange) {
                received.complete(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                headers.complete(exchange.getRequestHeaders().getFirst("Authorization"));
                path.complete(exchange.getRequestURI().toString());
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try (var exporter = exporter(server, ignored -> {})) {
            exporter.publish(sample(1));
            assertEquals(sample(1).lineProtocol(), received.get(3, TimeUnit.SECONDS));
            assertEquals("Token test-token", headers.get());
            assertTrue(path.get().contains("org=game+heist&bucket=metrics&precision=ms"));
            await(() -> exporter.delivered() == 1);
            assertEquals(0, exporter.failed());
        } finally { server.stop(0); }
    }
    @Test void slowSinkKeepsOnlyNewestWaitingSample() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        List<String> bodies = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/api/v2/write", exchange -> {
            try (exchange) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try (var exporter = exporter(server, ignored -> {})) {
            exporter.publish(sample(1));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 2; i <= 20; i++) exporter.publish(sample(i));
            assertEquals(18, exporter.dropped());
            release.countDown();
            await(() -> exporter.delivered() == 2);
            assertEquals(List.of(sample(1).lineProtocol(), sample(20).lineProtocol()), bodies);
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void failuresAreCountedWarningsAreBoundedAndLaterSamplesRecover() throws Exception {
        var code = new AtomicInteger(500);
        var warnings = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/api/v2/write", exchange -> {
            try (exchange) { exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(code.get(), -1); }
        });
        server.start();
        try (var exporter = exporter(server, ignored -> warnings.incrementAndGet())) {
            exporter.publish(sample(1));
            await(() -> exporter.failed() == 1);
            exporter.publish(sample(2));
            await(() -> exporter.failed() == 2);
            assertEquals(1, warnings.get());
            code.set(204);
            exporter.publish(sample(3));
            await(() -> exporter.delivered() == 1);
        } finally { server.stop(0); }
    }
    @Test void closedExporterDropsWithoutNetworkWork() {
        var exporter = new InfluxMetricsExporter(URI.create("http://127.0.0.1:1"), "org", "bucket", "token", ignored -> {});
        exporter.close();
        exporter.publish(sample(1));
        assertEquals(1, exporter.dropped());
        assertEquals(0, exporter.delivered());
    }
    private static MetricsSnapshot sample(long time) { return new MetricsSnapshot(time, "test", Map.of("online", time)); }
    private static InfluxMetricsExporter exporter(HttpServer server, java.util.function.Consumer<String> warning) {
        return new InfluxMetricsExporter(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "game heist", "metrics", "test-token", warning);
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for metrics worker");
    }
}
