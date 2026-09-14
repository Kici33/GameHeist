package dev.gameheist.paper;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.player.*;
import dev.gameheist.paper.config.ArenaLoader;
import dev.gameheist.runtime.instance.*;
import dev.gameheist.runtime.npc.*;
import dev.gameheist.runtime.persistence.InMemoryResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GuardedInstanceTest {
    @TempDir Path directory;

    @Test void guardAlarmPreservesProgressAndDoesNotLeakToOtherInstance() throws Exception {
        try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/arenas/graybox-v3.yml"))) {
            Files.copy(source, directory.resolve("arena.yml"));
        }
        var arenas = ArenaLoader.load(directory);
        var arena = arenas.require(new ArenaKey("graybox", 3));
        var manager = new InstanceManager(arenas, (id, definition) -> new WorldInstance() {
            @Override public String name() { return id.toString(); }
            @Override public void release() {}
        }, new InMemoryResultRepository(10), Clock.systemUTC(), LoadoutCatalog.starter(), 2);
        UUID first = manager.createPractice(arena.key(), Difficulty.NORMAL, 1);
        UUID second = manager.createPractice(arena.key(), Difficulty.NORMAL, 2);
        UUID firstPlayer = UUID.randomUUID(), secondPlayer = UUID.randomUUID();
        manager.join(first, firstPlayer, Loadout.starter(Role.TECHNICIAN));
        manager.join(second, secondPlayer, Loadout.starter(Role.TECHNICIAN));
        manager.start(first);
        manager.start(second);
        var security = arena.heist().orElseThrow().security();
        manager.interact(firstPlayer, security, security.center());
        var releases = new AtomicInteger();
        var squad = new GuardSquad(arena.guards(), Set.of(firstPlayer), definition -> new GuardActor() {
            private boolean released;
            @Override public boolean alive() { return !released; }
            @Override public Position position() { return definition.patrol().getFirst(); }
            @Override public Position eyePosition() { return position(); }
            @Override public boolean canSee(UUID id) { return true; }
            @Override public boolean moveTo(Position position, double speed) { return true; }
            @Override public void stop() {}
            @Override public void lookAt(Position position) {}
            @Override public void label(String text) {}
            @Override public void release() { if (!released) { released = true; releases.incrementAndGet(); } }
        }, () -> manager.raiseAlarm(first), message -> {});
        manager.own(first, squad);
        squad.start();
        Position visible = new Position(4.5, 65, -5.5, 0, 0);
        var players = List.of(new GuardPlayer(firstPlayer, visible, visible, false, false, Role.TECHNICIAN));
        for (int tick = 0; tick < 140; tick++) squad.tick(players, Optional.empty(), manager.snapshot(first).match().alarm());
        assertEquals(AlarmState.LOUD, manager.snapshot(first).match().alarm());
        assertEquals(AlarmState.STEALTH, manager.snapshot(second).match().alarm());
        assertTrue(manager.heistSnapshot(first).orElseThrow().securityDisabled());
        assertEquals(MatchPhase.VAULT, manager.snapshot(first).match().phase());
        manager.stop(first, "test_end");
        assertEquals(2, releases.get());
        squad.tick(players, Optional.empty(), AlarmState.STEALTH);
        assertEquals(2, releases.get());
        assertEquals(AlarmState.STEALTH, manager.snapshot(second).match().alarm());
        manager.shutdown();
    }
}
