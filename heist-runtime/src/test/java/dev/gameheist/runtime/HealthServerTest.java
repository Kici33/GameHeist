package dev.gameheist.runtime;

import dev.gameheist.runtime.health.HealthServer;
import dev.gameheist.runtime.health.DrainStatus;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class HealthServerTest {
    @Test void reportsStartupCapacityDrainAndStaleTickWithoutWorldAccess() throws Exception {
        AtomicLong now = new AtomicLong(1);
        try (var server = new HealthServer(new InetSocketAddress("127.0.0.1", 0), Duration.ofSeconds(15), now::get);
             var client = HttpClient.newHttpClient()) {
            assertEquals(503, status(client, server, "/live"));
            server.publish(true, 0);
            assertEquals(200, status(client, server, "/live"));
            assertEquals(200, status(client, server, "/ready"));
            server.publish(false, 1);
            assertEquals(200, status(client, server, "/live"));
            assertEquals(503, status(client, server, "/ready"));
            now.addAndGet(Duration.ofSeconds(16).toNanos());
            assertEquals(503, status(client, server, "/live"));
            assertEquals(404, status(client, server, "/live/extra"));
        }
    }
    private int status(HttpClient client, HealthServer server, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @Test void authenticatedDrainIsIdempotentAndAwaitsOwnerSnapshot() throws Exception {
        String token = "a".repeat(32);
        AtomicLong now = new AtomicLong(1);
        try (var server = new HealthServer(new InetSocketAddress("127.0.0.1", 0), Duration.ofSeconds(15), now::get, token);
             var client = HttpClient.newHttpClient()) {
            server.publish(true, 0);
            assertEquals(405, status(client, server, "/drain"));
            assertEquals(401, post(client, server, "/drain", "wrong"));
            assertFalse(server.drainRequested());
            assertEquals(404, post(client, server, "/drain/extra", token));
            assertEquals(202, post(client, server, "/drain", token));
            assertEquals(202, post(client, server, "/drain", token));
            assertTrue(server.drainRequested());
            assertEquals(503, status(client, server, "/ready"));
            assertEquals(503, status(client, server, "/drained"));
            server.publish(false, new DrainStatus(true, 1, 0, 0, true));
            assertEquals(503, status(client, server, "/drained"));
            server.publish(false, new DrainStatus(true, 0, 1, 0, true));
            assertEquals(503, status(client, server, "/drained"));
            server.publish(false, new DrainStatus(true, 0, 0, 1, true));
            assertEquals(503, status(client, server, "/drained"));
            server.publish(false, new DrainStatus(true, 0, 0, 0, false));
            assertEquals(503, status(client, server, "/drained"));
            server.publish(false, new DrainStatus(true, 0, 0, 0, true));
            assertEquals(200, status(client, server, "/drained"));
            assertEquals(200, status(client, server, "/live"));
            now.addAndGet(Duration.ofSeconds(16).toNanos());
            assertEquals(503, status(client, server, "/drained"));
        }
    }
    @Test void remoteDrainIsDisabledWithoutSecret() throws Exception {
        try (var server = new HealthServer(new InetSocketAddress("127.0.0.1", 0), Duration.ofSeconds(15), System::nanoTime);
             var client = HttpClient.newHttpClient()) {
            assertEquals(404, post(client, server, "/drain", "a".repeat(32)));
            assertFalse(server.drainRequested());
        }
    }
    private int post(HttpClient client, HealthServer server, String path, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
