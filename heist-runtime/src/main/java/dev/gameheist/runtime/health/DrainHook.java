package dev.gameheist.runtime.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Standalone preStop client, runnable from the bundled plugin jar without starting Paper. */
public final class DrainHook {
    private DrainHook() { }
    public static void main(String[] args) {
        int outcome;
        try {
            long seconds = Long.parseLong(System.getenv().getOrDefault("HEIST_DRAIN_TIMEOUT_SECONDS", "1250"));
            if (seconds < 1 || seconds > 86400 || args.length != 0) throw new IllegalArgumentException("Invalid drain budget");
            outcome = awaitDrain(URI.create("http://127.0.0.1:8081"), System.getenv("HEIST_DRAIN_TOKEN"), Duration.ofSeconds(seconds));
        } catch (IllegalArgumentException failure) { outcome = 2; }
        System.err.println(switch (outcome) {
            case 0 -> "GameHeist drain acknowledged; normal termination may proceed.";
            case 2 -> "GameHeist drain rejected: check local health endpoint and drain-token configuration.";
            default -> "GameHeist drain incomplete at deadline/interruption; termination may lose unacknowledged work.";
        });
        System.exit(outcome);
    }
    public static int awaitDrain(URI origin, String token, Duration budget) {
        if (origin == null || !"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) {
            throw new IllegalArgumentException("Drain hook requires a loopback HTTP origin");
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{32,128}")) throw new IllegalArgumentException("Invalid drain token");
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException("Invalid drain budget");
        long deadline = System.nanoTime() + budget.toNanos();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            boolean requested = false;
            while (deadline - System.nanoTime() > 0) {
                try {
                    var timeout = Duration.ofNanos(Math.max(1, Math.min(Duration.ofSeconds(3).toNanos(), deadline - System.nanoTime())));
                    var builder = HttpRequest.newBuilder(origin.resolve(requested ? "/drained" : "/drain")).timeout(timeout);
                    if (!requested) builder.header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody());
                    int status = client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (requested && status == 200) return 0;
                    if (status >= 400 && status < 500) return 2;
                    if (status >= 300 && status < 400) return 2;
                    if (!requested && status == 202) { requested = true; continue; }
                } catch (IOException unavailable) { /* Retry within the same total budget. */ }
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) Thread.sleep(Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(1).toNanos())));
            }
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        } finally { client.shutdownNow(); }
    }
}
