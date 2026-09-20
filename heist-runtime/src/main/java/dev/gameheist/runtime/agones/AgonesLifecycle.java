package dev.gameheist.runtime.agones;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Owner-thread adapter for Agones' loopback REST SDK. Calls are asynchronous and bounded to one in flight per type. */
public final class AgonesLifecycle implements AutoCloseable {
    private static final Pattern STATE = Pattern.compile("\\\"state\\\"\\s*:\\s*\\\"([A-Za-z]+)\\\"");
    private final HttpClient client;
    private final URI endpoint;
    private final Consumer<String> warning;
    private CompletableFuture<HttpResponse<String>> control, health;
    private String controlPath;
    private long nextPoll, nextHealth, lastWarning;
    private boolean readyRequested, shutdownRequested, closed;
    private State state = State.STARTING;

    public enum State { STARTING, READY, RESERVED, ALLOCATED, SHUTDOWN, UNHEALTHY, UNKNOWN }

    public static Optional<AgonesLifecycle> fromEnvironment(Consumer<String> warning) {
        String value = System.getenv("AGONES_SDK_HTTP_PORT");
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
            return Optional.of(new AgonesLifecycle(URI.create("http://127.0.0.1:" + port), warning));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid AGONES_SDK_HTTP_PORT", invalid);
        }
    }

    public AgonesLifecycle(URI endpoint, Consumer<String> warning) {
        if (endpoint == null || !"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1 || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Agones SDK must be an HTTP loopback origin");
        this.endpoint = endpoint;
        this.warning = java.util.Objects.requireNonNull(warning);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void tick(boolean locallyReady, boolean draining, boolean safeToStop) {
        if (closed) return;
        long now = System.nanoTime();
        finishHealth(now);
        finishControl(now);
        if (health == null && now >= nextHealth && state != State.SHUTDOWN) {
            health = post("/health");
            nextHealth = now + Duration.ofSeconds(2).toNanos();
        }
        if (control != null) return;
        if (draining && safeToStop && !shutdownRequested) {
            shutdownRequested = true;
            startControl("/shutdown", "POST");
        } else if (!draining && locallyReady && !readyRequested) {
            readyRequested = true;
            startControl("/ready", "POST");
        } else if (now >= nextPoll) {
            startControl("/gameserver", "GET");
            nextPoll = now + Duration.ofSeconds(1).toNanos();
        }
    }

    public boolean allocated() { return state == State.ALLOCATED; }
    public State state() { return state; }

    private void finishHealth(long now) {
        if (health == null || !health.isDone()) return;
        try { requireSuccess(health.join()); }
        catch (RuntimeException failure) { warn(now, "Agones health acknowledgement failed; Fleet health policy will decide replacement."); }
        health = null;
    }

    private void finishControl(long now) {
        if (control == null || !control.isDone()) return;
        try {
            var response = requireSuccess(control.join());
            if (controlPath.equals("/gameserver")) {
                if (response.body().length() > 65_536) throw new IllegalStateException("Agones response too large");
                var match = STATE.matcher(response.body());
                if (!match.find()) throw new IllegalStateException("Agones response omitted status.state");
                try { state = State.valueOf(match.group(1).toUpperCase(java.util.Locale.ROOT)); }
                catch (IllegalArgumentException unknown) { state = State.UNKNOWN; }
            } else if (controlPath.equals("/shutdown")) state = State.SHUTDOWN;
        } catch (RuntimeException failure) {
            if (controlPath.equals("/shutdown")) shutdownRequested = false;
            if (controlPath.equals("/ready")) readyRequested = false;
            warn(now, "Agones lifecycle request failed; readiness/allocation remains unconfirmed.");
        }
        control = null; controlPath = null;
    }

    private HttpResponse<String> requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Agones SDK status " + response.statusCode());
        return response;
    }
    private CompletableFuture<HttpResponse<String>> post(String path) { return request(path, "POST"); }
    private void startControl(String path, String method) { controlPath = path; control = request(path, method); }
    private CompletableFuture<HttpResponse<String>> request(String path, String method) {
        var builder = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json");
        var request = method.equals("POST") ? builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build() : builder.GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).toCompletableFuture();
    }
    private void warn(long now, String message) {
        if (lastWarning == 0 || now - lastWarning >= Duration.ofMinutes(1).toNanos()) { lastWarning = now; warning.accept(message); }
    }
    @Override public void close() {
        closed = true;
        if (control != null) control.cancel(true);
        if (health != null) health.cancel(true);
        client.close();
    }
}
