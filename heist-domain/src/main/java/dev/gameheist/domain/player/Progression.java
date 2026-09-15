package dev.gameheist.domain.player;

import java.util.*;

public record Progression(long experience, long wins, Set<String> cosmetics, long pendingRewards) {
    public Progression {
        if (experience < 0 || wins < 0 || pendingRewards < 0) throw new IllegalArgumentException("Negative progression");
        cosmetics = Set.copyOf(cosmetics);
    }
}
