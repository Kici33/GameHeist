package dev.gameheist.domain.network;

import java.util.*;

/** A registered server name is insufficient: bind admission to a process, match, and generation. */
public record BackendAssignment(String serverId, UUID incarnation, UUID matchId, long generation) {
    public BackendAssignment {
        if (serverId == null || !serverId.matches("[a-zA-Z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid backend ID");
        Objects.requireNonNull(incarnation);
        Objects.requireNonNull(matchId);
        if (generation < 1) throw new IllegalArgumentException("Invalid reservation generation");
    }
}
