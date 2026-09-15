package dev.gameheist.domain.player;

import dev.gameheist.domain.match.*;

/** Versioned sidegrade progression; never derive rewards from a client payload. */
public final class RewardPolicy {
    public static final int VERSION = 1;
    private RewardPolicy() { }
    public static long experience(MatchResult result) {
        if (result.practice() || result.outcome() != MatchOutcome.WON) return 0;
        return Math.addExact(100L, Math.multiplyExact(25L, result.securedBags()));
    }
}
