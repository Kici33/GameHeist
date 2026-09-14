package dev.gameheist.runtime;

import dev.gameheist.runtime.metrics.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only the disposable observability fixture; never the plugin's configured URL/token. */
@EnabledIfEnvironmentVariable(named = "HEIST_INFLUX_TESTS", matches = "true")
class InfluxIntegrationTest {
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
