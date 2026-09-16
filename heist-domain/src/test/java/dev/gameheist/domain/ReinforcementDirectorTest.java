package dev.gameheist.domain;

import dev.gameheist.domain.combat.*;
import dev.gameheist.domain.match.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ReinforcementDirectorTest {
    @Test void everyDifficultyCrewAndPhaseObeysCapsAndNeverCatchesUp() {
        for (var difficulty : Difficulty.values()) for (int crew = 1; crew <= 4; crew++)
            for (var phase : new MatchPhase[]{MatchPhase.INFILTRATION, MatchPhase.VAULT, MatchPhase.EXTRACTION}) {
                var clock = new Fixtures.MutableClock();
                var director = new ReinforcementDirector(clock, difficulty);
                int registered = 2;
                assertEquals(0, director.claim(false, phase, crew, 2, registered));
                assertTrue(director.remaining().isEmpty());
                assertEquals(0, director.claim(true, phase, crew, 2, registered));
                assertEquals(difficulty == Difficulty.HARD ? 12000 : 15000, director.remaining().orElseThrow());
                for (int wave = 0; wave < 4; wave++) {
                    clock.advance(Duration.ofHours(1));
                    int count = director.claim(true, phase, crew, 0, registered);
                    assertTrue(count >= 1 && count <= 4);
                    assertTrue(count <= BalanceRules.liveCap(difficulty, crew));
                    registered += count;
                    assertTrue(registered <= 24);
                    assertEquals(0, director.claim(true, phase, crew, count, registered));
                }
                clock.advance(Duration.ofDays(1));
                assertEquals(0, director.claim(true, phase, crew, 0, registered));
                assertEquals(4, director.waves());
                assertTrue(director.remaining().isEmpty());
            }
    }
    @Test void saturationCancelsDeadlineAndRecoveryGetsFreshWarningTime() {
        var clock = new Fixtures.MutableClock();
        var director = new ReinforcementDirector(clock, Difficulty.NORMAL);
        assertEquals(0, director.claim(true, MatchPhase.VAULT, 1, 4, 4));
        assertTrue(director.remaining().isEmpty());
        assertEquals(0, director.claim(true, MatchPhase.VAULT, 1, 3, 4));
        clock.advance(Duration.ofSeconds(15));
        assertEquals(1, director.claim(true, MatchPhase.VAULT, 1, 3, 4));
        assertEquals(0, director.claim(true, MatchPhase.VAULT, 1, 4, 5));
        assertTrue(director.remaining().isEmpty());
        assertEquals(0, director.claim(true, MatchPhase.VAULT, 1, 0, 5));
        assertEquals(35000, director.remaining().orElseThrow());
    }
    @Test void lifetimeCapAndTerminalPhasesPreventSpawning() {
        var clock = new Fixtures.MutableClock();
        var director = new ReinforcementDirector(clock, Difficulty.HARD);
        director.claim(true, MatchPhase.EXTRACTION, 4, 0, 23);
        clock.advance(Duration.ofSeconds(12));
        assertEquals(1, director.claim(true, MatchPhase.EXTRACTION, 4, 0, 23));
        assertEquals(0, director.claim(true, MatchPhase.EXTRACTION, 4, 0, 24));
        assertTrue(director.remaining().isEmpty());
        for (var phase : new MatchPhase[]{MatchPhase.BRIEFING, MatchPhase.FINALIZING, MatchPhase.CLOSED}) {
            assertEquals(0, director.claim(true, phase, 4, 0, 0));
            assertTrue(director.remaining().isEmpty());
        }
        assertEquals(0, director.claim(true, MatchPhase.VAULT, 0, 0, 0));
    }
    @Test void reducedCrewNeverExceedsItsNewLiveCapAndLootUsesOriginalCrew() {
        var clock = new Fixtures.MutableClock();
        var director = new ReinforcementDirector(clock, Difficulty.NORMAL);
        director.claim(true, MatchPhase.VAULT, 4, 5, 5);
        clock.advance(Duration.ofSeconds(15));
        assertEquals(0, director.claim(true, MatchPhase.VAULT, 1, 5, 5));
        assertEquals(0, director.waves());
        assertEquals(1, BalanceRules.requiredBags(Difficulty.NORMAL, 1, 2, 3));
        assertEquals(2, BalanceRules.requiredBags(Difficulty.HARD, 1, 2, 3));
        assertEquals(3, BalanceRules.requiredBags(Difficulty.NORMAL, 4, 2, 3));
        assertEquals(3, BalanceRules.requiredBags(Difficulty.HARD, 4, 2, 3));
        assertTrue(BalanceRules.guardDamage(Difficulty.HARD, 1) < BalanceRules.guardDamage(Difficulty.HARD, 4));
    }
}
