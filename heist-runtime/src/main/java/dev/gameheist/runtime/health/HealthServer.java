package dev.gameheist.runtime.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;
import java.util.function.LongSupplier;

/** HTTP threads only read immutable snapshots; they never access Bukkit or InstanceManager. */
public final class HealthServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final long staleAfterNanos;
    private final LongSupplier nanoTime;
    private final byte[] drainAuthorization;
    private final AtomicBoolean drainRequested = new AtomicBoolean();

    public HealthServer(InetSocketAddress address, Duration staleAfter, LongSupplier nanoTime) throws IOException {
        this(address, staleAfter, nanoTime, null);
    }
    public HealthServer(InetSocketAddress address, Duration staleAfter, LongSupplier nanoTime, String drainToken) throws IOException {
        if (staleAfter.isNegative() || staleAfter.isZero()) throw new IllegalArgumentException("Invalid health timeout");
        this.staleAfterNanos = staleAfter.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
        if (drainToken != null && !drainToken.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("Drain token must contain 32-128 URL-safe characters");
        }
        drainAuthorization = drainToken == null ? null : ("Bearer " + drainToken).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(address, 8);
        server.createContext("/live", exchange -> respond(exchange, "/live"));
        server.createContext("/ready", exchange -> respond(exchange, "/ready"));
        server.createContext("/drained", exchange -> respond(exchange, "/drained"));
        server.createContext("/drain", this::requestDrain);
        server.start();
    }

    public void publish(boolean accepting, int activeInstances) {
        publish(accepting, new DrainStatus(false, activeInstances, 0, 0, true));
    }
    public void publish(boolean accepting, DrainStatus status) {
        snapshot.set(new Snapshot(nanoTime.getAsLong(), accepting && !status.draining(), status));
    }
    public int port() { return server.getAddress().getPort(); }
    public boolean drainRequested() { return drainRequested.get(); }

    private void respond(HttpExchange exchange, String expectedPath) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals(expectedPath)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Snapshot current = snapshot.get();
            boolean live = current != null && nanoTime.getAsLong() - current.timestampNanos() <= staleAfterNanos;
            boolean healthy = live && switch (expectedPath) {
                case "/ready" -> current.accepting() && !drainRequested.get();
                case "/drained" -> current.drain().safeToStop();
                default -> true;
            };
            String body = "{\"healthy\":" + healthy + ",\"activeInstances\":"
                    + (current == null ? 0 : current.drain().activeInstances())
                    + ",\"draining\":" + (current != null && current.drain().draining())
                    + ",\"pendingProfileOperations\":" + (current == null ? 0 : current.drain().pendingProfileOperations())
                    + ",\"unacknowledgedProfileWrites\":" + (current == null ? 0 : current.drain().unacknowledgedProfileWrites())
                    + ",\"worldCleanupHealthy\":" + (current != null && current.drain().worldCleanupHealthy())
                    + ",\"safeToStop\":" + (live && current.drain().safeToStop()) + "}\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(healthy ? 200 : 503, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
    private void requestDrain(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/drain") || drainAuthorization == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !MessageDigest.isEqual(drainAuthorization, authorization.getBytes(StandardCharsets.UTF_8))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            drainRequested.set(true);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(202, -1);
        }
    }
    @Override public void close() { server.stop(0); }
    private record Snapshot(long timestampNanos, boolean accepting, DrainStatus drain) {}
}
