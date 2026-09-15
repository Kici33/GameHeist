package dev.gameheist.domain;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.player.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RewardPolicyTest {
    @Test void onlyProductionWinsEarnVersionOneProgression() {
        for (boolean practice : List.of(true, false)) for (MatchOutcome outcome : MatchOutcome.values()) {
            var result = new MatchResult(UUID.randomUUID(), new ArenaKey("graybox", 4), Difficulty.NORMAL,
                    1, practice, outcome, "test", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), AlarmState.LOUD,
                    Map.of(UUID.randomUUID(), Loadout.starter(Role.SCOUT)), Set.of(), 3);
            assertEquals(!practice && outcome == MatchOutcome.WON ? 175 : 0, RewardPolicy.experience(result));
        }
    }
}
