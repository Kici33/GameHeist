package dev.gameheist.runtime;

import dev.gameheist.runtime.metrics.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only the disposable observability fixture; never the plugin's configured URL/token. */
@EnabledIfEnvironmentVariable(named = "HEIST_INFLUX_TESTS", matches = "true")
class InfluxIntegrationTest {
    @Test void liveSinkRecoversAfterOutageAndSlowSinkKeepsNewestSample() throws Exception {
        String token = Objects.requireNonNull(System.getenv("HEIST_INFLUX_TEST_TOKEN"));
        String serverId = "recovery-" + UUID.randomUUID();
        var unavailable = new AtomicBoolean(true);
        var block = new AtomicBoolean(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var warnings = new AtomicInteger();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        try (var client = HttpClient.newHttpClient()) {
            proxy.createContext("/api/v2/write", exchange -> {
                try (exchange) {
                    var body = exchange.getRequestBody().readAllBytes();
                    if (unavailable.get()) { exchange.sendResponseHeaders(503, -1); return; }
                    if (block.getAndSet(false)) {
                        entered.countDown();
                        try { if (!release.await(2, TimeUnit.SECONDS)) throw new java.io.IOException("Gate timeout"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    }
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18086" + exchange.getRequestURI()))
                            .timeout(Duration.ofSeconds(3)).header("Authorization", "Token " + token)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    try { exchange.sendResponseHeaders(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(), -1); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            });
            proxy.start();
            try (var exporter = new InfluxMetricsExporter(URI.create("http://127.0.0.1:" + proxy.getAddress().getPort()),
                    "gameheist", "heist_metrics", token, ignored -> warnings.incrementAndGet())) {
                long time = System.currentTimeMillis() - 1000; // All sequence timestamps precede the query's stop time.
                exporter.publish(sample(time, serverId, 1));
                await(() -> exporter.failed() == 1);
                exporter.publish(sample(time + 1, serverId, 2));
                await(() -> exporter.failed() == 2 && warnings.get() == 1);
                unavailable.set(false);
                block.set(true);
                exporter.publish(sample(time + 2, serverId, 3));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (long sequence = 4; sequence <= 20; sequence++) exporter.publish(sample(time + sequence, serverId, sequence));
                assertEquals(16, exporter.dropped());
                release.countDown();
                await(() -> exporter.delivered() == 2);
                assertEquals(2, exporter.failed());
                String query = "from(bucket: \"heist_metrics\") |> range(start: -5m) |> filter(fn: (r) => r.server == \""
                        + serverId + "\" and r._field == \"acceptance_sequence\")";
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18086/api/v2/query?org=gameheist"))
                        .timeout(Duration.ofSeconds(5)).header("Authorization", "Token " + token)
                        .header("Content-Type", "application/vnd.flux").header("Accept", "application/csv")
                        .POST(HttpRequest.BodyPublishers.ofString(query)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var rows = response.body().lines().filter(line -> line.contains(serverId)).toList();
                assertEquals(2, rows.size(), response.body());
                assertTrue(rows.stream().anyMatch(line -> line.contains(",3,")));
                assertTrue(rows.stream().anyMatch(line -> line.contains(",20,")));
            } finally { release.countDown(); proxy.stop(0); }
        }
    }
    private static MetricsSnapshot sample(long time, String server, long sequence) {
        return new MetricsSnapshot(time, server, Map.of("acceptance_sequence", sequence));
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for live telemetry recovery");
    }
    @Test void exporterWritesQueryableInfluxData() throws Exception {
        String token = Objects.requireNonNull(System.getenv("HEIST_INFLUX_TEST_TOKEN"));
        String server = "test-" + UUID.randomUUID();
        Map<String, Number> fields = new HashMap<>();
        fields.put("tps_1m", 19.5);
        fields.put("server_tick_ms", 12.5);
        fields.put("heist_tick_mean_ms", 0.3);
        fields.put("heist_tick_max_ms", 1.2);
        for (String field : List.of("online_players", "active_instances", "crew_players", "loud_instances",
                "instances_ready", "instances_running", "instances_finalizing", "instances_closing", "draining",
                "safe_to_stop", "pending_profile_operations", "unacknowledged_profile_writes", "world_cleanup_healthy",
                "export_delivered_total", "export_failed_total", "export_dropped_total")) fields.put(field, 1L);
        try (var exporter = new InfluxMetricsExporter(URI.create("http://127.0.0.1:18086"), "gameheist", "heist_metrics", token, ignored -> {})) {
            exporter.publish(new MetricsSnapshot(System.currentTimeMillis(), server, fields));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (exporter.delivered() == 0 && exporter.failed() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, exporter.delivered(), "Local InfluxDB must acknowledge the sample");
        }
        String query = "from(bucket: \"heist_metrics\") |> range(start: -5m) |> filter(fn: (r) => r.server == \""
                + server + "\" and r._field == \"tps_1m\")";
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18086/api/v2/query?org=gameheist"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Token " + token)
                    .header("Content-Type", "application/vnd.flux").header("Accept", "application/csv")
                    .POST(HttpRequest.BodyPublishers.ofString(query)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains(",19.5,"));
            assertTrue(response.body().contains(server));
        }
    }
}
