package dev.gameheist.domain.combat;

import java.util.*;

public record CombatSnapshot(Map<UUID, Player> players, Map<String, Integer> guards, boolean waveSpawned,
                             OptionalLong waveRemainingMillis) {
    public CombatSnapshot(Map<UUID, Player> players, Map<String, Integer> guards, boolean waveSpawned) {
        this(players, guards, waveSpawned, OptionalLong.empty());
    }
    public CombatSnapshot {
        players = Map.copyOf(players); guards = Map.copyOf(guards);
        Objects.requireNonNull(waveRemainingMillis);
        if (waveRemainingMillis.isPresent() && (waveSpawned || waveRemainingMillis.getAsLong() < 0))
            throw new IllegalArgumentException("Invalid pending wave countdown");
    }
    public record Player(int health, int ammunition, long reloadMillis, Optional<UUID> reviving,
                         long reviveMillis, CombatStats stats, boolean medkitAvailable) {
        public boolean downed() { return health == 0; }
    }
}
