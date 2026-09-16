package dev.gameheist.domain.combat;

import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.npc.GuardDefinition;
import java.time.Duration;

/** Provisional version-1 tuning. Real-client balance acceptance is recorded separately. */
public final class BalanceRules {
    private BalanceRules() { }
    public static int liveCap(Difficulty difficulty, int crew) { return 2 + crew * 2 + (difficulty == Difficulty.HARD ? 2 : 0); }
    public static int guardDamage(Difficulty difficulty, int crew) { return (crew == 1 ? 6 : 10) + (difficulty == Difficulty.HARD ? 2 : 0); }
    public static int requiredBags(Difficulty difficulty, int crew, int authored, int available) {
        return Math.min(available, Math.max(1, authored + (crew == 1 ? -1 : crew >= 3 ? 1 : 0) + (difficulty == Difficulty.HARD ? 1 : 0)));
    }
    public static GuardDefinition guard(GuardDefinition base, Difficulty difficulty) {
        if (difficulty == Difficulty.NORMAL) return base;
        return new GuardDefinition(base.id(), base.patrol(), Math.min(32, base.sightRange() + 2),
                Math.min(180, base.fieldOfView() + 10), Duration.ofMillis(Math.max(1000, base.detectionTime().toMillis() * 4 / 5)),
                base.alarmTime(), base.searchTime(), base.speed());
    }
}
