package dev.gameheist.domain;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.objective.*;
import dev.gameheist.domain.player.LoadoutCatalog;
import java.time.*;
import java.util.*;

public final class Fixtures {
    private Fixtures() {}
    public static ObjectiveDefinition objective(String id, MatchPhase phase, String... dependencies) {
        return new ObjectiveDefinition(id, ObjectiveType.INTERACT, phase, Set.of(dependencies), true);
    }
    public static ArenaDefinition arena(List<ObjectiveDefinition> objectives) {
        var minimum = new Position(-32, 64, -32, 0, 0);
        return new ArenaDefinition(new ArenaKey("bank", 1), "Bank",
                new Bounds(minimum, new Position(32, 96, 32, 0, 0)), new Position(0, 65, 0, 0, 0),
                4, Duration.ofMinutes(20), objectives);
    }
    public static ArenaDefinition arena() {
        return arena(List.of(objective("security", MatchPhase.INFILTRATION),
                objective("drill", MatchPhase.VAULT, "security"),
                objective("loot", MatchPhase.VAULT, "drill"),
                objective("escape", MatchPhase.EXTRACTION, "loot")));
    }
    public static Match match(Clock clock) {
        return new Match(UUID.randomUUID(), arena(), Difficulty.NORMAL, 42, true, clock, LoadoutCatalog.starter());
    }
    public static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-12T12:00:00Z");
        public void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
