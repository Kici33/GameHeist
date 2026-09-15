package dev.gameheist.mongo;

import com.mongodb.client.MongoClients;
import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.network.*;
import dev.gameheist.domain.player.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.mongodb.client.model.Filters.*;

/** Disposable local replica set only; never use the application database or URI. */
@EnabledIfEnvironmentVariable(named = "HEIST_REWARD_TESTS", matches = "true")
class MongoRewardsIntegrationTest {
    @BeforeAll static void initializeDisposableReplicaSet() throws Exception {
        try (var client = MongoClients.create(URI)) {
            var admin = client.getDatabase("admin");
            if (!admin.runCommand(new org.bson.Document("hello", 1)).containsKey("setName"))
                admin.runCommand(new org.bson.Document("replSetInitiate", new org.bson.Document("_id", "heist-test")
                        .append("members", List.of(new org.bson.Document("_id", 0).append("host", "127.0.0.1:27029")))));
            for (int i = 0; i < 100; i++) {
                if (admin.runCommand(new org.bson.Document("hello", 1)).getBoolean("isWritablePrimary", false)) return;
                Thread.sleep(100);
            }
            throw new IllegalStateException("Disposable replica set did not elect a primary");
        }
    }
    private static final String URI = "mongodb://127.0.0.1:27029/?directConnection=true";
    private final String database = "heist_reward_test_" + UUID.randomUUID().toString().replace("-", "");
    private MongoStore store;
    @BeforeEach void open() { store = new MongoStore(URI, database); }
    @AfterEach void close() {
        store.close();
        try (var client = MongoClients.create(URI)) { client.getDatabase(database).drop(); }
    }

    @Test void replayOneHundredTimesAndRestartAppliesExactlyOnce() throws Exception {
        var f = fixture();
        for (int i = 0; i < 100; i++) await(store.rewards().accept(f.reservation, f.backend, f.result));
        assertEquals(1, await(store.rewards().progression(f.player)).pendingRewards());
        assertEquals(0, await(store.rewards().progression(f.player)).experience());
        try (var restarted = new MongoStore(URI, database)) {
            assertEquals(1, await(restarted.rewards().recover(16)));
            for (int i = 0; i < 100; i++) {
                await(restarted.rewards().accept(f.reservation, f.backend, f.result));
                assertEquals(0, await(restarted.rewards().recover(16)));
            }
            var saved = await(restarted.rewards().progression(f.player));
            assertEquals(175, saved.experience()); assertEquals(1, saved.wins());
            assertEquals(0, saved.pendingRewards()); assertTrue(saved.cosmetics().contains("brass"));
        }
    }

    @Test void leaderboardRequiresAuthorityAndInvalidationReconcilesTotalsAndCache() throws Exception {
        var f = fixture();
        var scope = new StatisticsScope(f.result.arena(), f.result.difficulty(), 1, false);
        var timed = timed(f.result, 30000);
        var practice = new MatchResult(UUID.randomUUID(), timed.arena(), timed.difficulty(), 1, true, MatchOutcome.WON,
                "debug", timed.createdAt(), timed.finishedAt(), timed.alarm(), timed.participants(), Set.of(), 3, Map.of(), OptionalLong.of(1));
        await(store.save(practice));
        var unowned = new MatchResult(UUID.randomUUID(), timed.arena(), timed.difficulty(), 1, false, MatchOutcome.WON,
                "unowned", timed.createdAt(), timed.finishedAt(), timed.alarm(), timed.participants(), Set.of(), 3, Map.of(), OptionalLong.of(1));
        await(store.save(unowned));
        assertTrue(await(store.leaderboards().leaderboard(scope)).isEmpty());
        assertEquals(PlayerStatistics.empty(), await(store.statistics(f.player, scope)));
        await(store.rewards().accept(f.reservation, f.backend, timed));
        await(store.rewards().accept(f.reservation, f.backend, timed));
        assertEquals(List.of(new LeaderboardEntry(f.player, 1, 30000, 30000)), await(store.leaderboards().leaderboard(scope)));
        var stats = await(store.statistics(f.player, scope));
        assertEquals(30000, stats.gameplayMillis()); assertEquals(1, stats.timedRuns());
        assertEquals(new ObjectiveStats(3, 2), stats.objectiveStats());
        assertTrue(await(store.leaderboards().leaderboard(new StatisticsScope(scope.arena(), Difficulty.HARD, 1, false))).isEmpty());
        assertTrue(await(store.leaderboards().leaderboard(new StatisticsScope(scope.arena(), scope.difficulty(), 2, false))).isEmpty());
        store.leaderboards().clearCache();
        assertEquals(1, await(store.leaderboards().leaderboard(scope)).size());
        await(store.leaderboards().invalidate(timed.matchId(), "invalid run"));
        assertTrue(await(store.leaderboards().leaderboard(scope)).isEmpty());
        assertEquals(PlayerStatistics.empty(), await(store.statistics(f.player, scope)));
        await(store.rewards().accept(f.reservation, f.backend, timed));
        assertTrue(await(store.leaderboards().leaderboard(scope)).isEmpty());
    }

    @Test void leaderboardTiesAreStableAndLegacyMissingTimeIsNotZero() throws Exception {
        var first = fixture(); var second = fixture(); var legacy = fixture();
        for (var f : List.of(first, second)) await(store.rewards().accept(f.reservation, f.backend, timed(f.result, 12345)));
        await(store.rewards().accept(legacy.reservation, legacy.backend, legacy.result));
        var scope = new StatisticsScope(first.result.arena(), first.result.difficulty(), 1, false);
        var expected = List.of(first.player, second.player).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertEquals(expected, await(store.leaderboards().leaderboard(scope)).stream().map(LeaderboardEntry::playerId).toList());
        var old = await(store.statistics(legacy.player, scope));
        assertEquals(1, old.wins()); assertEquals(0, old.timedRuns()); assertTrue(old.bestWinMillis().isEmpty());
        try (var restarted = new MongoStore(URI, database)) {
            assertEquals(await(store.leaderboards().leaderboard(scope)), await(restarted.leaderboards().leaderboard(scope)));
        }
    }

    @Test void retentionOnlyMarksPracticeAndDoesNotChangeRetryIdentity() throws Exception {
        var f = fixture();
        var practice = copy(f.result, true, 3);
        try (var retaining = new MongoStore(URI, database, 7)) { await(retaining.save(practice)); }
        try (var differentPolicy = new MongoStore(URI, database, 14)) { await(differentPolicy.save(practice)); }
        try (var client = MongoClients.create(URI)) {
            var collection = client.getDatabase(database).getCollection("match_results");
            assertEquals(Date.from(practice.finishedAt().plus(Duration.ofDays(7))), collection.find(eq("_id", practice.matchId().toString())).first().getDate("expiresAt"));
        }
        var other = fixture();
        await(store.rewards().accept(other.reservation, other.backend, timed(other.result, 30000)));
        try (var client = MongoClients.create(URI)) {
            var db = client.getDatabase(database);
            var collection = db.getCollection("match_results");
            assertFalse(collection.find(eq("_id", other.result.matchId().toString())).first().containsKey("expiresAt"));
            collection.deleteOne(eq("_id", practice.matchId().toString())); // model TTL eviction without waiting for monitor
            assertEquals(1, collection.countDocuments());
            assertEquals(1, db.getCollection("result_eligibility").countDocuments());
        }
    }

    private static MatchResult timed(MatchResult r, long millis) {
        return new MatchResult(r.matchId(), r.arena(), r.difficulty(), r.seed(), r.practice(), r.outcome(), r.reason(),
                r.createdAt(), r.finishedAt(), r.alarm(), r.participants(), r.completedObjectives(), r.securedBags(), r.combatStats(),
                OptionalLong.of(millis), Map.of(r.participants().keySet().iterator().next(), new ObjectiveStats(3, 2)));
    }

    @Test void competingWorkersAndReceiptReplayDoNotDuplicateProgression() throws Exception {
        var f = fixture();
        await(store.rewards().accept(f.reservation, f.backend, f.result));
        try (var second = new MongoStore(URI, database)) {
            var a = store.rewards().recover(16); var b = second.rewards().recover(16);
            await(a); await(b);
            assertEquals(175, await(store.rewards().progression(f.player)).experience());
        }
        // Simulate a scan replay after a lost acknowledgement: receipt must win over the pending flag.
        try (var client = MongoClients.create(URI)) {
            client.getDatabase(database).getCollection("reward_jobs").updateMany(new org.bson.Document(),
                    new org.bson.Document("$set", new org.bson.Document("done", false)));
        }
        assertEquals(0, await(store.rewards().recover(16)));
        assertEquals(1, await(store.rewards().progression(f.player)).wins());
    }

    @Test void staleGenerationConflictingPayloadAndPracticeAreRejected() throws Exception {
        var f = fixture();
        var stale = new BackendAssignment(f.backend.serverId(), f.backend.incarnation(), f.result.matchId(), 2);
        assertThrows(ExecutionException.class, () -> await(store.rewards().accept(f.reservation, stale, f.result)));
        assertThrows(ExecutionException.class, () -> await(store.rewards().accept(f.reservation, f.backend, copy(f.result, true, 3))));
        await(store.rewards().accept(f.reservation, f.backend, f.result));
        assertThrows(ExecutionException.class, () -> await(store.rewards().accept(f.reservation, f.backend, copy(f.result, false, 4))));
        await(store.reservations().release(f.reservation, f.backend));
        await(store.rewards().accept(f.reservation, f.backend, f.result));
        await(store.rewards().recover(16));
        assertEquals(175, await(store.rewards().progression(f.player)).experience());
    }

    @Test void profileEditCannotOverwriteRewardProgression() throws Exception {
        var f = fixture();
        var old = await(store.loadOrCreate(f.player));
        await(store.rewards().accept(f.reservation, f.backend, f.result));
        await(store.rewards().recover(16));
        await(store.save(new PlayerProfile(f.player, 1, old.presets(), 0, old.settings().change("sound", "off")), 0));
        assertEquals(175, await(store.rewards().progression(f.player)).experience());
        assertFalse(await(store.loadOrCreate(f.player)).settings().soundEnabled());
    }

    @Test void corruptJobRollsBackAndDoesNotAwardAnything() throws Exception {
        var f = fixture();
        await(store.rewards().accept(f.reservation, f.backend, f.result));
        try (var client = MongoClients.create(URI)) {
            client.getDatabase(database).getCollection("reward_jobs").updateMany(new org.bson.Document(),
                    new org.bson.Document("$set", new org.bson.Document("experience", 999L)));
        }
        assertEquals(0, await(store.rewards().recover(16)));
        var pending = await(store.rewards().progression(f.player));
        assertEquals(0, pending.experience()); assertEquals(1, pending.pendingRewards());
        try (var client = MongoClients.create(URI)) {
            assertEquals(0, client.getDatabase(database).getCollection("reward_receipts").countDocuments());
        }
    }

    @Test void failureAfterReceiptInsertRollsBackBothThenRecovers() throws Exception {
        var f = fixture();
        await(store.rewards().accept(f.reservation, f.backend, f.result));
        try (var client = MongoClients.create(URI)) {
            var db = client.getDatabase(database);
            db.createCollection("player_progression", new com.mongodb.client.model.CreateCollectionOptions().validationOptions(
                    new com.mongodb.client.model.ValidationOptions().validator(new org.bson.Document("blocked", true))));
        }
        assertEquals(0, await(store.rewards().recover(16)));
        try (var client = MongoClients.create(URI)) {
            var db = client.getDatabase(database);
            assertEquals(0, db.getCollection("reward_receipts").countDocuments());
            assertEquals(0, db.getCollection("player_progression").countDocuments());
            assertEquals(1, db.getCollection("reward_jobs").countDocuments(eq("done", false)));
            db.runCommand(new org.bson.Document("collMod", "player_progression").append("validator", new org.bson.Document()));
            db.getCollection("reward_jobs").updateMany(new org.bson.Document(),
                    new org.bson.Document("$unset", new org.bson.Document("retryAfter", "")));
        }
        assertEquals(1, await(store.rewards().recover(16)));
        assertEquals(175, await(store.rewards().progression(f.player)).experience());
    }

    private Fixture fixture() throws Exception {
        UUID player = UUID.randomUUID(), reservation = UUID.randomUUID(), match = UUID.randomUUID();
        var backend = new BackendAssignment("test", UUID.randomUUID(), match, 1);
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        var result = new MatchResult(match, new ArenaKey("graybox", 4), Difficulty.NORMAL, 1, false,
                MatchOutcome.WON, "extracted", now, now.plusSeconds(60), AlarmState.LOUD,
                Map.of(player, Loadout.starter(Role.SCOUT)), Set.of(), 3);
        await(store.reservations().reserve(new CrewReservation(reservation, result.arena(), result.difficulty(),
                result.participants(), now, now.plusSeconds(120))));
        await(store.reservations().assign(reservation, backend));
        await(store.reservations().admit(reservation, player, backend, UUID.randomUUID()));
        return new Fixture(player, reservation, backend, result);
    }
    private static MatchResult copy(MatchResult r, boolean practice, int bags) {
        return new MatchResult(r.matchId(), r.arena(), r.difficulty(), r.seed(), practice, r.outcome(), r.reason(),
                r.createdAt(), r.finishedAt(), r.alarm(), r.participants(), r.completedObjectives(), bags);
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(30, TimeUnit.SECONDS); }
    private record Fixture(UUID player, UUID reservation, BackendAssignment backend, MatchResult result) { }
}
