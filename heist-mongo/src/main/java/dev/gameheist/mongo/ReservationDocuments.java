package dev.gameheist.mongo;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.network.*;
import dev.gameheist.domain.player.*;
import org.bson.Document;
import java.util.*;

final class ReservationDocuments {
    private ReservationDocuments() { }
    static Document request(CrewReservation value) {
        var crew = value.crew().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Document("playerId", entry.getKey().toString()).append("loadout", MongoDocuments.loadout(entry.getValue()))).toList();
        return new Document("_id", value.id().toString()).append("schemaVersion", 1)
                .append("arena", new Document("id", value.arena().id()).append("version", value.arena().version()))
                .append("difficulty", value.difficulty().name()).append("crew", crew)
                .append("crewIds", value.crew().keySet().stream().map(UUID::toString).sorted().toList())
                .append("createdAt", Date.from(value.createdAt())).append("expiresAt", Date.from(value.expiresAt()));
    }
    static Document assignment(BackendAssignment value) {
        return new Document("serverId", value.serverId()).append("incarnation", value.incarnation().toString())
                .append("matchId", value.matchId().toString()).append("generation", value.generation());
    }
    static ReservationSnapshot snapshot(Document value) {
        if (!Integer.valueOf(1).equals(value.getInteger("schemaVersion"))) throw new IllegalStateException("Unsupported reservation schema");
        Map<UUID, Loadout> crew = new HashMap<>();
        for (var member : value.getList("crew", Document.class)) {
            var loadout = member.get("loadout", Document.class);
            crew.put(UUID.fromString(member.getString("playerId")), MongoDocuments.loadout(loadout));
        }
        var arena = value.get("arena", Document.class);
        var request = new CrewReservation(UUID.fromString(value.getString("_id")), new ArenaKey(arena.getString("id"), arena.getInteger("version")),
                Difficulty.valueOf(value.getString("difficulty")), crew, value.getDate("createdAt").toInstant(), value.getDate("expiresAt").toInstant());
        Optional<BackendAssignment> assignment = Optional.empty();
        if (value.getBoolean("assigned")) {
            var target = value.get("assignment", Document.class);
            assignment = Optional.of(new BackendAssignment(target.getString("serverId"), UUID.fromString(target.getString("incarnation")),
                    UUID.fromString(target.getString("matchId")), target.getLong("generation")));
        }
        Map<UUID, UUID> attempts = new HashMap<>();
        value.get("claims", Document.class).forEach((player, attempt) -> attempts.put(UUID.fromString(player), UUID.fromString((String) attempt)));
        return new ReservationSnapshot(request, assignment, attempts, value.getBoolean("active"));
    }
}
