package dev.gameheist.paper;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.player.*;
import dev.gameheist.paper.config.ArenaLoader;
import dev.gameheist.runtime.instance.*;
import dev.gameheist.runtime.persistence.InMemoryResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalInstanceTest {
    @TempDir Path directory;

    @Test void manifestToRuntimeLoopIsIsolatedAndFinalizesOnce() throws Exception {
        try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/arenas/graybox-v2.yml"))) {
            Files.copy(source, directory.resolve("v2.yml"));
        }
        var registry = ArenaLoader.load(directory);
        var clock = new MutableClock();
        var released = new AtomicInteger();
        var results = new InMemoryResultRepository(100);
        var manager = new InstanceManager(registry, (id, arena) -> new WorldInstance() {
            @Override public String name() { return "world-" + id; }
            @Override public void release() { released.incrementAndGet(); }
        }, results, clock, LoadoutCatalog.starter(), 2);
        var key = new ArenaKey("graybox", 2);
        var definition = registry.require(key).heist().orElseThrow();
        UUID a = manager.createPractice(key, Difficulty.NORMAL, 42);
        UUID b = manager.createPractice(key, Difficulty.NORMAL, 42);
        UUID player = UUID.randomUUID(), outsider = UUID.randomUUID();
        manager.join(a, player, Loadout.starter(Role.SCOUT));
        manager.join(b, outsider, Loadout.starter(Role.SCOUT));
        assertThrows(IllegalStateException.class, () -> manager.interact(player, definition.security(), definition.security().center()));
        manager.start(a);
        manager.start(b);
        assertThrows(IllegalStateException.class, () -> manager.completeObjective(a, "security"));
        manager.interact(player, definition.security(), definition.security().center());
        manager.interact(player, definition.drill(), definition.drill().center());
        for (int jam = 0; jam < 2; jam++) {
            clock.advance(Duration.ofSeconds(90));
            manager.updateHeist(a, Map.of(player, definition.drill().center()));
            assertTrue(manager.heistSnapshot(a).orElseThrow().jammed());
            manager.interact(player, definition.drill(), definition.drill().center());
            clock.advance(Duration.ofSeconds(6));
            manager.updateHeist(a, Map.of(player, definition.drill().center()));
        }
        clock.advance(Duration.ofSeconds(90));
        manager.updateHeist(a, Map.of(player, definition.drill().center()));
        for (var bag : definition.bags().subList(0, definition.requiredBags())) {
            manager.interact(player, bag, bag.center());
            manager.interact(player, definition.extraction(), definition.extraction().center());
        }
        assertFalse(manager.heistSnapshot(b).orElseThrow().securityDisabled());
        assertTrue(manager.heistSnapshot(b).orElseThrow().carriedBags().isEmpty());
        manager.interact(player, definition.extraction(), definition.extraction().center());
        clock.advance(definition.extractionDuration());
        manager.updateHeist(a, Map.of(player, definition.extraction().center()));
        manager.tick();
        manager.tick();
        assertEquals(1, results.all().size());
        assertEquals(3, results.all().getFirst().securedBags());
        assertEquals(MatchOutcome.WON, results.all().getFirst().outcome());
        assertEquals(1, released.get());
        assertTrue(manager.instanceOf(player).isEmpty());
        assertEquals(b, manager.instanceOf(outsider).orElseThrow());
        assertEquals(1, manager.all().size());
        manager.shutdown();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-12T12:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
