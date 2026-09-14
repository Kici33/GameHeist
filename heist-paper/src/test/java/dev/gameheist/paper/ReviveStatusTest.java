package dev.gameheist.paper;

import dev.gameheist.domain.combat.*;
import dev.gameheist.paper.gameplay.ReviveStatus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviveStatusTest {
    private final UUID target = UUID.randomUUID(), helper = UUID.randomUUID();
    private CombatSnapshot.Player fighter(int hp, UUID recipient, long remaining) {
        return new CombatSnapshot.Player(hp, 12, 0, Optional.ofNullable(recipient), remaining,
                new CombatStats(0, 0, 0), true);
    }
    @Test void recipientSeesRemainingTimeAndReturnsToWaitingOnInterruption() {
        var active = new CombatSnapshot(Map.of(target, fighter(0, null, 0), helper, fighter(50, target, 2001)), Map.of(), false);
        assertEquals("Being revived by Alice · 3s", ReviveStatus.forRecipient(target, active, Map.of(helper, "Alice")));
        var interrupted = new CombatSnapshot(Map.of(target, fighter(0, null, 0), helper, fighter(50, null, 0)), Map.of(), false);
        assertEquals("DOWNED — wait for a teammate to revive you", ReviveStatus.forRecipient(target, interrupted, Map.of()));
        assertEquals("", ReviveStatus.forRecipient(helper, interrupted, Map.of()));
    }
    @Test void fastestLivingRescuerWinsAndOtherTargetsAreExcluded() {
        UUID faster = UUID.randomUUID(), unrelated = UUID.randomUUID();
        var snapshot = new CombatSnapshot(Map.of(target, fighter(0, null, 0), helper, fighter(50, target, 3000),
                faster, fighter(50, target, 1000), unrelated, fighter(50, UUID.randomUUID(), 1)), Map.of(), false);
        assertEquals("Being revived by Bob · 1s", ReviveStatus.forRecipient(target, snapshot, Map.of(faster, "Bob")));
        var downedHelper = new CombatSnapshot(Map.of(target, fighter(0, null, 0), helper, fighter(0, target, 1)), Map.of(), false);
        assertEquals("DOWNED — wait for a teammate to revive you", ReviveStatus.forRecipient(target, downedHelper, Map.of()));
    }
}
