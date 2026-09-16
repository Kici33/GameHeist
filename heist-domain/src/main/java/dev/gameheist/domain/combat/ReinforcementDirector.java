package dev.gameheist.domain.combat;

import dev.gameheist.domain.match.*;
import java.time.*;
import java.util.*;

/** Bounded pressure, not catch-up spawning. All deadlines use the match clock. */
public final class ReinforcementDirector {
    private final Clock clock;
    private final Difficulty difficulty;
    private Instant due;
    private int waves;
    public ReinforcementDirector(Clock clock, Difficulty difficulty) {
        this.clock = Objects.requireNonNull(clock); this.difficulty = Objects.requireNonNull(difficulty);
    }
    public int claim(boolean loud, MatchPhase phase, int crew, int alive, int registered) {
        if (crew < 0 || crew > 4 || alive < 0 || registered < alive || registered > 24) throw new IllegalArgumentException("Invalid director census");
        if (!phase.gameplay() || !loud || crew == 0 || waves >= 4 || registered == 24) { due = null; return 0; }
        Instant now = clock.instant();
        int capacity = Math.max(0, Math.min(24 - registered, BalanceRules.liveCap(difficulty, crew) - alive));
        if (capacity == 0) { due = null; return 0; }
        if (due == null) { due = now.plusSeconds(waves == 0 ? (difficulty == Difficulty.HARD ? 12 : 15) : cooldown(phase)); return 0; }
        if (now.isBefore(due)) return 0;
        int requested = Math.min(4, (crew + 1) / 2 + (difficulty == Difficulty.HARD ? 1 : 0)
                + (phase == MatchPhase.EXTRACTION ? 1 : 0));
        int count = Math.min(capacity, requested);
        waves++;
        due = waves == 4 || registered + count == 24 ? null : now.plusSeconds(cooldown(phase));
        return count;
    }
    private long cooldown(MatchPhase phase) {
        long base = difficulty == Difficulty.HARD ? 30 : 40;
        return base - (phase == MatchPhase.EXTRACTION ? 10 : phase == MatchPhase.VAULT ? 5 : 0);
    }
    public int waves() { return waves; }
    public OptionalLong remaining() {
        return due == null ? OptionalLong.empty() : OptionalLong.of(Math.max(0, Duration.between(clock.instant(), due).toMillis()));
    }
}
