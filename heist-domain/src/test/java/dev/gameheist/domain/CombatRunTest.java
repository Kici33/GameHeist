package dev.gameheist.domain;

import dev.gameheist.domain.combat.*;
import dev.gameheist.domain.player.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatRunTest {
    private final Fixtures.MutableClock clock = new Fixtures.MutableClock();
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
    private CombatRun combat() {
        var combat = new CombatRun(Map.of(first, Loadout.starter(Role.SCOUT), second, Loadout.starter(Role.SUPPORT),
                third, Loadout.starter(Role.TECHNICIAN)), clock);
        combat.registerGuard("guard");
        return combat;
    }
    private void advance(long millis) { clock.advance(Duration.ofMillis(millis)); }
    private CombatSnapshot.Player player(CombatRun combat, UUID id) { return combat.snapshot().players().get(id); }
    private void hit(CombatRun combat, UUID target) {
        advance(2500);
        assertEquals(CombatRun.Attack.AIMING, combat.attack("guard", Optional.of(target), 5, true, true));
        advance(1000);
        assertEquals(CombatRun.Attack.HIT, combat.attack("guard", Optional.of(target), 5, true, true));
    }
    private void down(CombatRun combat, UUID target) { for (int i = 0; i < 10; i++) hit(combat, target); }

    @Test void fireRateAmmoAndReloadCannotBeBypassedByRepeatedInput() {
        var combat = combat();
        for (int i = 0; i < 12; i++) {
            assertTrue(combat.fire(first, Optional.empty(), 24, true));
            assertFalse(combat.fire(first, Optional.empty(), 24, true));
            advance(300);
        }
        assertEquals(0, player(combat, first).ammunition());
        assertFalse(combat.fire(first, Optional.empty(), 1, true));
        assertTrue(combat.reload(first));
        assertFalse(combat.reload(first));
        advance(1999);
        assertFalse(combat.fire(first, Optional.empty(), 1, true));
        advance(1);
        assertTrue(combat.fire(first, Optional.empty(), 1, true));
        assertEquals(11, player(combat, first).ammunition());
    }
    @Test void onlyClearInRangeHitsDamageRegisteredGuardsAndStatsDoNotOvercount() {
        var combat = combat();
        for (double invalid : List.of(-1d, 25d, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(combat.fire(first, Optional.of("guard"), invalid, true));
            advance(300);
        }
        combat.fire(first, Optional.of("guard"), 5, false);
        advance(300);
        combat.fire(first, Optional.of("outsider"), 5, true);
        assertEquals(60, combat.snapshot().guards().get("guard"));
        for (int i = 0; i < 4; i++) { advance(300); combat.fire(first, Optional.of("guard"), 24, true); }
        assertEquals(0, combat.snapshot().guards().get("guard"));
        assertEquals(60, player(combat, first).stats().damageDealt());
        assertEquals(CombatRun.Attack.NONE, combat.attack("guard", Optional.of(first), 5, true, true));
        assertThrows(IllegalArgumentException.class, () -> combat.fire(UUID.randomUUID(), Optional.of("guard"), 5, true));
    }
    @Test void coverRangeAndRetargetingRestartTheTelegraph() {
        var combat = combat();
        assertEquals(CombatRun.Attack.NONE, combat.attack("guard", Optional.of(first), 5, true, false));
        assertEquals(CombatRun.Attack.AIMING, combat.attack("guard", Optional.of(first), 5, true, true));
        advance(999);
        assertEquals(CombatRun.Attack.NONE, combat.attack("guard", Optional.of(first), 5, false, true));
        advance(1000);
        assertEquals(CombatRun.Attack.AIMING, combat.attack("guard", Optional.of(first), 5, true, true));
        advance(1000);
        assertEquals(CombatRun.Attack.AIMING, combat.attack("guard", Optional.of(second), 5, true, true));
        assertEquals(CombatRun.Attack.NONE, combat.attack("guard", Optional.of(second), 17, true, true));
        assertEquals(CombatRun.Attack.AIMING, combat.attack("guard", Optional.of(second), 16, true, true));
        advance(1000);
        assertEquals(CombatRun.Attack.HIT, combat.attack("guard", Optional.of(second), 16, true, true));
        assertEquals(100, player(combat, first).health());
        assertEquals(90, player(combat, second).health());
        advance(60_000);
        assertEquals(CombatRun.Attack.AIMING, combat.attack("guard", Optional.of(second), 5, true, true));
        assertEquals(90, player(combat, second).health());
    }
    @Test void downedPlayersCannotShootReloadOrReviveAndSupportRevivesOnce() {
        var combat = combat();
        down(combat, first);
        assertFalse(combat.active(first));
        assertFalse(combat.defeated());
        assertFalse(combat.fire(first, Optional.of("guard"), 5, true));
        assertFalse(combat.reload(first));
        assertFalse(combat.beginRevive(first, first, 0, true));
        assertTrue(combat.beginRevive(second, first, 3, true));
        assertTrue(combat.beginRevive(third, first, 3, true));
        advance(2999);
        assertFalse(combat.updateRevive(second, 3, true, true));
        advance(1);
        assertTrue(combat.updateRevive(second, 3, true, true));
        advance(1000);
        assertFalse(combat.updateRevive(third, 3, true, true));
        assertEquals(50, player(combat, first).health());
        assertEquals(1, player(combat, second).stats().revives());
        assertEquals(0, player(combat, third).stats().revives());
        assertEquals(100, player(combat, first).stats().damageTaken());
    }
    @Test void reviveCancelsOnDamageDistanceCoverReleaseAndWeaponUse() {
        var combat = combat();
        down(combat, first);
        assertFalse(combat.beginRevive(second, first, 4, true));
        assertFalse(combat.beginRevive(second, first, 2, false));
        assertTrue(combat.beginRevive(second, first, 2, true));
        hit(combat, second);
        assertTrue(player(combat, second).reviving().isEmpty());
        assertFalse(combat.updateRevive(second, 2, true, true));
        for (int interruption = 0; interruption < 3; interruption++) {
            assertTrue(combat.beginRevive(second, first, 2, true));
            advance(5000);
            assertFalse(combat.updateRevive(second, interruption == 0 ? 4 : 2, interruption != 1, interruption != 2));
            assertTrue(player(combat, second).reviving().isEmpty());
        }
        combat.beginRevive(second, first, 2, true);
        assertTrue(combat.fire(second, Optional.empty(), 24, true));
        assertTrue(player(combat, second).reviving().isEmpty());
        combat.beginRevive(second, first, 2, true);
        assertTrue(combat.reload(second));
        assertTrue(player(combat, second).reviving().isEmpty());
    }
    @Test void reinforcementWaveIsDelayedAndClaimedOnlyOnceAndGuardCapIsHard() {
        var combat = combat();
        assertFalse(combat.claimWave(false));
        advance(60_000);
        assertFalse(combat.claimWave(true));
        advance(14_999);
        assertFalse(combat.claimWave(true));
        advance(1);
        assertTrue(combat.claimWave(true));
        advance(60_000);
        assertFalse(combat.claimWave(true));
        for (int i = 1; i < 24; i++) combat.registerGuard("guard_" + i);
        assertThrows(IllegalStateException.class, () -> combat.registerGuard("overflow"));
        assertThrows(IllegalStateException.class, () -> combat.registerGuard("guard"));
    }
    @Test void independentMatchesDoNotShareHealthAmmoOrContributions() {
        var firstRun = combat();
        var other = combat();
        hit(firstRun, first);
        firstRun.fire(first, Optional.of("guard"), 5, true);
        assertEquals(100, player(other, first).health());
        assertEquals(12, player(other, first).ammunition());
        assertEquals(new CombatStats(0, 0, 0), player(other, first).stats());
        assertThrows(UnsupportedOperationException.class, () -> other.snapshot().guards().clear());
    }
    @Test void soloPressureIsReducedAndLethalDamageIsCappedAtRemainingHealth() {
        var combat = new CombatRun(Map.of(first, Loadout.starter(Role.SCOUT)), clock);
        combat.registerGuard("guard");
        hit(combat, first);
        assertEquals(94, player(combat, first).health());
        for (int i = 1; i < 17; i++) hit(combat, first);
        assertTrue(combat.defeated());
        assertEquals(100, player(combat, first).stats().damageTaken());
        assertEquals(CombatRun.Attack.NONE, combat.attack("guard", Optional.of(first), 5, true, true));
    }
}
