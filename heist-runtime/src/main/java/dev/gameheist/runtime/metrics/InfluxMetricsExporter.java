package dev.gameheist.runtime.metrics;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Best-effort telemetry. One HTTP request plus one queued snapshot; never a gameplay dependency. */
public final class InfluxMetricsExporter implements AutoCloseable {
    private final HttpClient client;
    private final URI endpoint;
    private final String token;
    private final Consumer<String> warning;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor worker;
    private long lastWarningNanos;
    private boolean warned;

    public InfluxMetricsExporter(URI baseUri, String organization, String bucket, String token, Consumer<String> warning) {
        if (baseUri == null || !("http".equals(baseUri.getScheme()) || "https".equals(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getUserInfo() != null || baseUri.getQuery() != null
                || baseUri.getFragment() != null || !(baseUri.getPath().isEmpty() || baseUri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Metrics URL must be an HTTP(S) origin without credentials or query");
        }
        if (token == null || !token.matches("[\\x21-\\x7e]{1,1024}")) throw new IllegalArgumentException("HEIST_INFLUX_TOKEN is required and must be a single printable token");
        endpoint = baseUri.resolve("/api/v2/write?org=" + parameter(organization) + "&bucket=" + parameter(bucket) + "&precision=ms");
        this.token = token;
        this.warning = java.util.Objects.requireNonNull(warning);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon().name("heist-metrics-", 0).factory(), (task, executor) -> {
                    if (executor.isShutdown()) throw new RejectedExecutionException("Metrics exporter closed");
                    if (executor.getQueue().poll() != null) dropped.incrementAndGet();
                    executor.execute(task);
                });
    }
    public synchronized void publish(MetricsSnapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot);
        if (worker.isShutdown()) { dropped.incrementAndGet(); return; }
        worker.execute(() -> send(snapshot));
    }
    private void send(MetricsSnapshot snapshot) {
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Token " + token).header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(snapshot.lineProtocol(), StandardCharsets.UTF_8)).build();
            if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 204) {
                deliveryFailed();
                return;
            }
            delivered.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failed.incrementAndGet();
        } catch (Exception failure) { deliveryFailed(); }
    }
    private void deliveryFailed() {
        failed.incrementAndGet();
        long now = System.nanoTime();
        if (!warned || now - lastWarningNanos >= Duration.ofMinutes(1).toNanos()) {
            warned = true;
            lastWarningNanos = now;
            warning.accept("Metrics delivery failed; monitoring samples may be lost. Gameplay continues. Check InfluxDB configuration/connectivity.");
        }
    }
    public long delivered() { return delivered.get(); }
    public long failed() { return failed.get(); }
    public long dropped() { return dropped.get(); }
    private static String parameter(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid InfluxDB organization or bucket");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    @Override public synchronized void close() {
        dropped.addAndGet(worker.shutdownNow().size());
        client.shutdownNow();
    }
}
