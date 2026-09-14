package dev.gameheist.domain.combat;

import java.util.*;

public record CombatSnapshot(Map<UUID, Player> players, Map<String, Integer> guards, boolean waveSpawned) {
    public CombatSnapshot { players = Map.copyOf(players); guards = Map.copyOf(guards); }
    public record Player(int health, int ammunition, long reloadMillis, Optional<UUID> reviving,
                         long reviveMillis, CombatStats stats, boolean medkitAvailable) {
        public boolean downed() { return health == 0; }
    }
}
