package dev.gameheist.runtime;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.player.*;
import dev.gameheist.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    @Test void staleProfileCannotOverwriteNewerSettings() {
        var repository = new InMemoryProfileRepository();
        UUID id = UUID.randomUUID();
        var initial = repository.loadOrCreate(id).toCompletableFuture().join();
        var updated = new PlayerProfile(id, 1, List.of(Loadout.starter(Role.SUPPORT)), 0, initial.settings());
        repository.save(updated, 0).toCompletableFuture().join();
        var stale = new PlayerProfile(id, 1, initial.presets(), 0, initial.settings());
        assertThrows(CompletionException.class, () -> repository.save(stale, 0).toCompletableFuture().join());
        assertEquals(updated, repository.loadOrCreate(id).toCompletableFuture().join());
    }
    @Test void resultsAreIdempotentButConflictsFail() {
        var repository = new InMemoryResultRepository(100);
        UUID id = UUID.randomUUID();
        var original = result(id, "first");
        for (int i = 0; i < 100; i++) repository.save(original).toCompletableFuture().join();
        assertEquals(1, repository.all().size());
        assertThrows(CompletionException.class, () -> repository.save(result(id, "conflict")).toCompletableFuture().join());
        assertEquals(original, repository.all().getFirst());
    }
    @Test void practiceHistoryIsBounded() {
        var repository = new InMemoryResultRepository(2);
        for (int i = 0; i < 3; i++) repository.save(result(UUID.randomUUID(), "test")).toCompletableFuture().join();
        assertEquals(2, repository.all().size());
    }
    private MatchResult result(UUID id, String reason) {
        Instant now = Instant.parse("2026-09-12T12:00:00Z");
        return new MatchResult(id, new ArenaKey("bank", 1), Difficulty.NORMAL, 1, true,
                MatchOutcome.ABORTED, reason, now, now, AlarmState.STEALTH, Map.of(), Set.of());
    }
}
