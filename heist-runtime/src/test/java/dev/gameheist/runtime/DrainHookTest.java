package dev.gameheist.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.gameheist.runtime.health.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DrainHookTest {
    private static final String TOKEN = "test".repeat(8);
    @Test void waitsForPublishedDrainAcknowledgement() throws Exception {
        try (var server = new HealthServer(new InetSocketAddress("127.0.0.1", 0), Duration.ofSeconds(15), System::nanoTime, TOKEN)) {
            server.publish(false, new DrainStatus(true, 0, 0, 0, true));
            assertEquals(0, DrainHook.awaitDrain(origin(server.port()), TOKEN, Duration.ofSeconds(2)));
            assertTrue(server.drainRequested());
        }
    }
    @Test void rejectsWrongTokenAndDisabledDrainInsteadOfClaimingSuccess() throws Exception {
        try (var server = new HealthServer(new InetSocketAddress("127.0.0.1", 0), Duration.ofSeconds(15), System::nanoTime, TOKEN)) {
            assertEquals(2, DrainHook.awaitDrain(origin(server.port()), "wrong".repeat(8), Duration.ofSeconds(1)));
        }
        try (var server = new HealthServer(new InetSocketAddress("127.0.0.1", 0), Duration.ofSeconds(15), System::nanoTime)) {
            assertEquals(2, DrainHook.awaitDrain(origin(server.port()), TOKEN, Duration.ofSeconds(1)));
        }
    }
    @Test void unfinishedDrainExitsAtBudget() throws Exception {
        try (var server = new HealthServer(new InetSocketAddress("127.0.0.1", 0), Duration.ofSeconds(15), System::nanoTime, TOKEN)) {
            server.publish(false, new DrainStatus(true, 1, 0, 0, true));
            assertEquals(1, DrainHook.awaitDrain(origin(server.port()), TOKEN, Duration.ofMillis(150)));
        }
    }
    @Test void pollsUntilPendingWorkClears() throws Exception {
        var polls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/drain", exchange -> {
            try (exchange) {
                if (exchange.getRequestURI().getPath().equals("/drained")) {
                    exchange.sendResponseHeaders(polls.incrementAndGet() == 1 ? 503 : 200, -1);
                } else exchange.sendResponseHeaders(202, -1);
            }
        });
        server.start();
        try {
            assertEquals(0, DrainHook.awaitDrain(origin(server.getAddress().getPort()), TOKEN, Duration.ofSeconds(3)));
            assertEquals(2, polls.get());
        } finally { server.stop(0); }
    }
    @Test void neverSendsManagementSecretOutsideLoopback() {
        assertThrows(IllegalArgumentException.class, () -> DrainHook.awaitDrain(URI.create("http://example.com"), TOKEN, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> DrainHook.awaitDrain(origin(8081), "bad", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> DrainHook.awaitDrain(origin(8081), TOKEN, Duration.ZERO));
    }
    private static URI origin(int port) { return URI.create("http://127.0.0.1:" + port); }
}
