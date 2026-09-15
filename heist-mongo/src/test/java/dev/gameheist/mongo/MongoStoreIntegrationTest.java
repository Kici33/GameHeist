package dev.gameheist.mongo;

import com.mongodb.client.MongoClients;
import dev.gameheist.domain.player.*;
import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in isolated fixture only: never reads the application's database URI. */
@EnabledIfEnvironmentVariable(named = "HEIST_MONGO_TESTS", matches = "true")
class MongoStoreIntegrationTest {
    @Test void bestWinUsesOnlyTimedWinsAndMatchesMemory() throws Exception {
        UUID player = UUID.randomUUID();
        var scope = new StatisticsScope(new ArenaKey("graybox", 4), Difficulty.NORMAL, 1, true);
        var memory = new dev.gameheist.runtime.persistence.InMemoryResultRepository(10);
        for (MatchOutcome outcome : MatchOutcome.values()) {
            for (var duration : List.of(OptionalLong.empty(), OptionalLong.of(65000), OptionalLong.of(90000))) {
                var result = new MatchResult(UUID.randomUUID(), scope.arena(), Difficulty.NORMAL, 1, true,
                        outcome, "timing", Instant.EPOCH, Instant.EPOCH.plusSeconds(90), AlarmState.LOUD,
                        Map.of(player, Loadout.starter(Role.SCOUT)), Set.of(), 0, Map.of(),
                        outcome == MatchOutcome.WON ? duration : OptionalLong.of(1));
                await(store.save(result));
                await(store.save(result));
                await(memory.save(result));
            }
        }
        assertEquals(65000, await(store.statistics(player, scope)).bestWinMillis().orElseThrow());
        assertEquals(await(memory.statistics(player, scope)), await(store.statistics(player, scope)));
    }
    @Test void combatTotalsMatchMemoryIncludingMissingPersonalAndLegacyStats() throws Exception {
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var roster = Map.of(player, Loadout.starter(Role.SCOUT), other, Loadout.starter(Role.SUPPORT));
        var scope = new StatisticsScope(new ArenaKey("graybox", 4), Difficulty.NORMAL, 2, true);
        var memory = new dev.gameheist.runtime.persistence.InMemoryResultRepository(10);
        var contributions = List.of(Map.of(player, new dev.gameheist.domain.combat.CombatStats(120, 30, 1),
                        other, new dev.gameheist.domain.combat.CombatStats(40, 100, 0)),
                Map.of(other, new dev.gameheist.domain.combat.CombatStats(10, 5, 2)),
                Map.<UUID, dev.gameheist.domain.combat.CombatStats>of());
        for (var stats : contributions) {
            var result = new MatchResult(UUID.randomUUID(), scope.arena(), Difficulty.NORMAL, 1, true,
                    MatchOutcome.WON, "combat", Instant.EPOCH, Instant.EPOCH.plusSeconds(20), AlarmState.LOUD,
                    roster, Set.of(), 3, stats);
            await(store.save(result));
            await(store.save(result));
            await(memory.save(result));
        }
        try (var reopened = new MongoStore(URI, database)) {
            assertEquals(new PlayerStatistics(3, 0, 0, 0, 9, 120, 30, 1), await(reopened.statistics(player, scope)));
            assertEquals(new PlayerStatistics(3, 0, 0, 0, 9, 50, 105, 2), await(reopened.statistics(other, scope)));
            assertEquals(await(memory.statistics(player, scope)), await(reopened.statistics(player, scope)));
            assertEquals(await(memory.statistics(other, scope)), await(reopened.statistics(other, scope)));
        }
    }
    private static final String URI = "mongodb://127.0.0.1:27028/?directConnection=true";
    private final String database = "heist_test_" + UUID.randomUUID().toString().replace("-", "");
    private MongoStore store;
    @BeforeEach void open() { store = new MongoStore(URI, database); }
    @AfterEach void close() {
        store.close();
        try (var client = MongoClients.create(URI)) { client.getDatabase(database).drop(); }
    }
    @Test void profilesSurviveNewClientAndConcurrentCreate() throws Exception {
        UUID id = UUID.randomUUID();
        var first = store.loadOrCreate(id);
        var second = store.loadOrCreate(id);
        assertEquals(await(first), await(second));
        var replacement = scout(await(first));
        await(store.save(replacement, 0));
        try (var another = new MongoStore(URI, database)) { assertEquals(replacement, await(another.loadOrCreate(id))); }
    }
    @Test void revisionRaceAllowsOneWriterAndIdenticalRetry() throws Exception {
        var original = await(store.loadOrCreate(UUID.randomUUID()));
        var scout = scout(original);
        var support = new PlayerProfile(original.playerId(), 1, List.of(Loadout.starter(Role.SUPPORT)), 0, original.settings());
        var a = store.save(scout, 0).toCompletableFuture();
        var b = store.save(support, 0).toCompletableFuture();
        a.handle((v, e) -> null).get(10, TimeUnit.SECONDS);
        b.handle((v, e) -> null).get(10, TimeUnit.SECONDS);
        assertNotEquals(a.isCompletedExceptionally(), b.isCompletedExceptionally());
        var winner = await(store.loadOrCreate(original.playerId()));
        await(store.save(winner, 0));
        assertEquals(winner, await(store.loadOrCreate(original.playerId())));
    }
    @Test void resultsAreDurableIdempotentAndRejectConflictingRetry() throws Exception {
        UUID id = UUID.randomUUID();
        var result = result(id, "complete");
        await(store.save(result));
        await(store.save(result));
        assertThrows(ExecutionException.class, () -> await(store.save(result(id, "different"))));
        try (var client = MongoClients.create(URI)) {
            var collection = client.getDatabase(database).getCollection("match_results");
            assertEquals(1, collection.countDocuments());
            assertEquals(MongoDocuments.result(result), collection.find().first());
        }
    }
    @Test void futureSchemaCannotBeOverwritten() throws Exception {
        UUID id = UUID.randomUUID();
        try (var client = MongoClients.create(URI)) {
            client.getDatabase(database).getCollection("profiles").insertOne(new Document("_id", id.toString())
                    .append("schemaVersion", 99).append("revision", 0L));
        }
        assertThrows(ExecutionException.class, () -> await(store.loadOrCreate(id)));
        assertThrows(ExecutionException.class, () -> await(store.save(scout(PlayerProfile.starter(id)), 0)));
    }
    @Test void closedStoreRejectsNewWorkWithoutBlockingCaller() {
        store.close();
        assertTrue(store.loadOrCreate(UUID.randomUUID()).toCompletableFuture().isCompletedExceptionally());
    }
    @Test void statisticsMatchMemoryRulesAcrossEveryPartitionAndNewClients() throws Exception {
        var memory = new dev.gameheist.runtime.persistence.InMemoryResultRepository(100);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (boolean practice : List.of(true, false)) {
            for (int version : List.of(2, 3)) {
                for (Difficulty difficulty : List.of(Difficulty.NORMAL, Difficulty.HARD)) {
                    for (int crewSize : List.of(1, 2)) {
                        for (MatchOutcome outcome : MatchOutcome.values()) {
                            var roster = crewSize == 1 ? Map.of(player, Loadout.starter(Role.SCOUT))
                                    : Map.of(player, Loadout.starter(Role.SCOUT), other, Loadout.starter(Role.SUPPORT));
                            var result = new MatchResult(UUID.randomUUID(), new ArenaKey("graybox", version), difficulty,
                                    42, practice, outcome, "stats_test", Instant.EPOCH, Instant.EPOCH.plusSeconds(30),
                                    AlarmState.STEALTH, roster, Set.of(), 3);
                            await(store.save(result));
                            await(store.save(result));
                            await(memory.save(result));
                        }
                    }
                }
            }
        }
        try (var reopened = new MongoStore(URI, database)) {
            for (boolean practice : List.of(true, false)) {
                for (int version : List.of(2, 3)) {
                    for (Difficulty difficulty : List.of(Difficulty.NORMAL, Difficulty.HARD)) {
                        for (int crewSize : List.of(1, 2)) {
                            var scope = new StatisticsScope(new ArenaKey("graybox", version), difficulty, crewSize, practice);
                            assertEquals(new PlayerStatistics(1, 1, 1, 1, 9), await(reopened.statistics(player, scope)));
                            assertEquals(await(memory.statistics(other, scope)), await(reopened.statistics(other, scope)));
                        }
                    }
                }
            }
            var empty = new StatisticsScope(new ArenaKey("graybox", 99), Difficulty.NORMAL, 1, true);
            assertEquals(PlayerStatistics.empty(), await(reopened.statistics(player, empty)));
        }
    }
    @Test void loudWinDoesNotCountAsStealthCompletion() throws Exception {
        UUID player = UUID.randomUUID();
        var result = new MatchResult(UUID.randomUUID(), new ArenaKey("graybox", 3), Difficulty.NORMAL, 1,
                true, MatchOutcome.WON, "loud", Instant.EPOCH, Instant.EPOCH.plusSeconds(30), AlarmState.LOUD,
                Map.of(player, Loadout.starter(Role.SCOUT)), Set.of(), 5);
        await(store.save(result));
        assertEquals(new PlayerStatistics(1, 0, 0, 0, 5), await(store.statistics(player,
                new StatisticsScope(result.arena(), result.difficulty(), 1, true))));
    }
    private static PlayerProfile scout(PlayerProfile original) {
        return new PlayerProfile(original.playerId(), original.revision() + 1, List.of(Loadout.starter(Role.SCOUT)), 0, original.settings());
    }
    private static MatchResult result(UUID id, String reason) {
        return new MatchResult(id, new ArenaKey("graybox", 3), Difficulty.NORMAL, 123, true, MatchOutcome.WON,
                reason, Instant.EPOCH, Instant.EPOCH.plusSeconds(20), AlarmState.STEALTH,
                Map.of(UUID.fromString("00000000-0000-0000-0000-000000000001"), Loadout.starter(Role.SCOUT)), Set.of("escape"), 3);
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(10, TimeUnit.SECONDS); }
}
