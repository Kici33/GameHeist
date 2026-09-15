package dev.gameheist.mongo;

import dev.gameheist.domain.player.*;
import dev.gameheist.domain.match.MatchResult;
import org.bson.Document;
import java.util.*;

/** Explicit versioned storage format, independent of Java class names. */
final class MongoDocuments {
    private MongoDocuments() { }
    static Document loadout(Loadout value) {
        var document = new Document("role", value.role().name()).append("weapon", value.weaponId()).append("gadget", value.gadgetId());
        if (!value.name().equals("Preset")) document.append("name", value.name());
        if (!value.cosmeticId().equals("default")) document.append("cosmetic", value.cosmeticId());
        return document;
    }
    static Loadout loadout(Document value) {
        return new Loadout(Role.valueOf(value.getString("role")), value.getString("weapon"), value.getString("gadget"),
                value.getString("name") == null ? "Preset" : value.getString("name"),
                value.getString("cosmetic") == null ? "default" : value.getString("cosmetic"));
    }
    static Document profile(PlayerProfile value) {
        return new Document("_id", value.playerId().toString()).append("schemaVersion", 1)
                .append("revision", value.revision()).append("selectedPreset", value.selectedPreset())
                .append("presets", value.presets().stream().map(MongoDocuments::loadout).toList())
                .append("settings", new Document("language", value.settings().language())
                        .append("soundEnabled", value.settings().soundEnabled())
                        .append("reducedParticles", value.settings().reducedParticles())
                        .append("reducedMotion", value.settings().reducedMotion())
                        .append("notificationsEnabled", value.settings().notificationsEnabled()));
    }
    static PlayerProfile profile(Document value) {
        if (value == null || !Integer.valueOf(1).equals(value.getInteger("schemaVersion"))) {
            throw new IllegalStateException("Unsupported profile schema");
        }
        var settings = value.get("settings", Document.class);
        Object revision = value.get("revision");
        if (!(revision instanceof Long) && !(revision instanceof Integer)) throw new IllegalStateException("Invalid profile revision type");
        return new PlayerProfile(UUID.fromString(value.getString("_id")), ((Number) revision).longValue(),
                value.getList("presets", Document.class).stream().map(MongoDocuments::loadout).toList(),
                value.getInteger("selectedPreset"), new PlayerSettings(settings.getString("language"),
                        settings.getBoolean("soundEnabled"), settings.getBoolean("reducedParticles"),
                        settings.getBoolean("reducedMotion", false), settings.getBoolean("notificationsEnabled", true)));
    }
    static Document result(MatchResult value) {
        var participants = value.participants().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new Document("playerId", e.getKey().toString()).append("loadout", loadout(e.getValue()))).toList();
        var document = new Document("_id", value.matchId().toString()).append("schemaVersion", 1)
                .append("arena", new Document("id", value.arena().id()).append("version", value.arena().version()))
                .append("difficulty", value.difficulty().name()).append("seed", value.seed())
                .append("practice", value.practice()).append("outcome", value.outcome().name())
                .append("reason", value.reason()).append("createdAt", value.createdAt().toString())
                .append("finishedAt", value.finishedAt().toString()).append("alarm", value.alarm().name())
                .append("participants", participants).append("completedObjectives", value.completedObjectives().stream().sorted().toList())
                .append("securedBags", value.securedBags());
        value.gameplayMillis().ifPresent(duration -> document.append("gameplayMillis", duration));
        // Optional additive field keeps older non-combat result retry identities unchanged.
        if (!value.combatStats().isEmpty()) document.append("combatStats", value.combatStats().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(e -> new Document("playerId", e.getKey().toString())
                        .append("damageDealt", e.getValue().damageDealt()).append("damageTaken", e.getValue().damageTaken())
                        .append("revives", e.getValue().revives())).toList());
        if (!value.objectiveStats().isEmpty()) document.append("objectiveStats", value.objectiveStats().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(e -> new Document("playerId", e.getKey().toString())
                        .append("actions", e.getValue().actions()).append("securedBags", e.getValue().securedBags())).toList());
        return document;
    }
}
