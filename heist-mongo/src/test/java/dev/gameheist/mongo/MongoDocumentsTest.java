package dev.gameheist.mongo;

import dev.gameheist.domain.player.*;
import org.junit.jupiter.api.Test;
import org.bson.Document;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MongoDocumentsTest {
    @Test void roundTripsNamedPresetsAndAccessibilityPreferences() {
        var profile = new PlayerProfile(UUID.randomUUID(), 7,
                List.of(new Loadout(Role.SUPPORT, "carbine", "medkit", "Ekipa medyczna", "default")), 0,
                new PlayerSettings("pl", false, true, true, false));
        assertEquals(profile, MongoDocuments.profile(Document.parse(MongoDocuments.profile(profile).toJson())));
        assertEquals(profile.selectedLoadout(), MongoDocuments.loadout(MongoDocuments.loadout(profile.selectedLoadout())));
    }

    @Test void oldProfileDefaultsNewPreferencesWithoutResettingExistingOnes() {
        var old = new PlayerProfile(UUID.randomUUID(), 13, List.of(Loadout.starter(Role.SCOUT)), 0,
                new PlayerSettings("pl", false, true));
        var document = MongoDocuments.profile(old);
        var settings = document.get("settings", Document.class);
        settings.remove("reducedMotion");
        settings.remove("notificationsEnabled");
        assertEquals(old, MongoDocuments.profile(document));
        assertEquals(Set.of("role", "weapon", "gadget"), MongoDocuments.loadout(old.selectedLoadout()).keySet());
    }
    @Test void roundTripsEveryPresetAndSetting() {
        var profile = new PlayerProfile(UUID.randomUUID(), 42, List.of(Loadout.starter(Role.SCOUT),
                Loadout.starter(Role.SUPPORT), Loadout.starter(Role.ENFORCER)), 2, new PlayerSettings("en", false, true));
        assertEquals(profile, MongoDocuments.profile(Document.parse(MongoDocuments.profile(profile).toJson())));
    }
    @Test void rejectsFutureSchemaInsteadOfResettingProgress() {
        var document = MongoDocuments.profile(PlayerProfile.starter(UUID.randomUUID()));
        document.put("schemaVersion", 2);
        assertThrows(IllegalStateException.class, () -> MongoDocuments.profile(document));
    }
    @Test void combatResultsSerializeDeterministicallyWithoutChangingLegacyShape() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var crew = Map.of(first, Loadout.starter(Role.SCOUT), second, Loadout.starter(Role.SUPPORT));
        var now = java.time.Instant.parse("2026-09-14T12:00:00Z");
        var old = new dev.gameheist.domain.match.MatchResult(UUID.randomUUID(), new dev.gameheist.domain.arena.ArenaKey("graybox", 4),
                dev.gameheist.domain.match.Difficulty.NORMAL, 1, true, dev.gameheist.domain.match.MatchOutcome.WON, "extracted",
                now, now, dev.gameheist.domain.match.AlarmState.LOUD, crew, Set.of(), 3);
        assertFalse(MongoDocuments.result(old).containsKey("combatStats"));
        var stats = new LinkedHashMap<UUID, dev.gameheist.domain.combat.CombatStats>();
        stats.put(second, new dev.gameheist.domain.combat.CombatStats(20, 10, 1));
        stats.put(first, new dev.gameheist.domain.combat.CombatStats(60, 100, 0));
        var result = new dev.gameheist.domain.match.MatchResult(old.matchId(), old.arena(), old.difficulty(), old.seed(), old.practice(),
                old.outcome(), old.reason(), old.createdAt(), old.finishedAt(), old.alarm(), crew, Set.of(), 3, stats);
        var document = MongoDocuments.result(result);
        var values = Document.parse(document.toJson()).getList("combatStats", Document.class);
        assertEquals(first.toString(), values.getFirst().getString("playerId"));
        assertEquals(60, values.getFirst().getInteger("damageDealt"));
        assertEquals(100, values.getFirst().getInteger("damageTaken"));
        assertEquals(1, values.getLast().getInteger("revives"));
        var changed = new Document(document);
        changed.remove("combatStats");
        assertEquals(MongoDocuments.result(old), changed);
        assertEquals(document, MongoDocuments.result(result));
        assertFalse(document.containsKey("gameplayMillis"));
        var timed = new dev.gameheist.domain.match.MatchResult(old.matchId(), old.arena(), old.difficulty(), old.seed(), old.practice(),
                old.outcome(), old.reason(), old.createdAt(), old.finishedAt(), old.alarm(), crew, Set.of(), 3, stats, OptionalLong.of(65432));
        var timedDocument = MongoDocuments.result(timed);
        assertEquals(65432L, timedDocument.getLong("gameplayMillis"));
        timedDocument.remove("gameplayMillis");
        assertEquals(document, timedDocument);
    }
}
