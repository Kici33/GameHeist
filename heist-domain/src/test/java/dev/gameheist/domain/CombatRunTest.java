package dev.gameheist.domain;

import dev.gameheist.domain.combat.*;
import dev.gameheist.domain.player.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatRunTest {
    @Test void emptyTriggerReloadsOnceWithoutFiringOrExtendingTheTimer() {
        var combat = combat();
        assertFalse(combat.reloadEmpty(first));
        for (int i = 0; i < CombatRun.MAGAZINE; i++) {
            assertTrue(combat.fire(first, Optional.empty(), 1, true));
            advance(300);
        }
        assertTrue(combat.reloadEmpty(first));
        for (int i = 0; i < 19; i++) {
            advance(100);
            assertFalse(combat.reloadEmpty(first));
            assertFalse(combat.canFire(first));
        }
        assertEquals(100, player(combat, first).reloadMillis());
        advance(100);
        assertFalse(combat.reloadEmpty(first));
        assertEquals(CombatRun.MAGAZINE, player(combat, first).ammunition());
        assertTrue(combat.canFire(first));
        assertEquals(0, player(combat, first).stats().damageDealt());
    }
    @Test void startingRevivePreservesElapsedReloadButCancelsUnfinishedReload() {
        for (long elapsed : List.of(1999L, 2000L)) {
            var combat = combat();
            down(combat, first);
            combat.fire(second, Optional.empty(), 1, true);
            combat.reload(second);
            advance(elapsed);
            assertTrue(combat.beginRevive(second, first, 2, true));
            advance(3000);
            assertEquals(elapsed == 2000 ? 12 : 11, player(combat, second).ammunition());
            assertTrue(combat.updateRevive(second, 2, true, true));
        }
    }
    @Test void reviveInterruptionReportsReasonOnceAndDiscardsProgress() {
        var combat = combat();
        down(combat, first);
        assertTrue(combat.beginRevive(second, first, 2, true));
        advance(2000);
        assertEquals(CombatRun.ReviveUpdate.IN_PROGRESS, combat.updateReviveDetailed(second, 2, true, true));
        assertEquals(CombatRun.ReviveUpdate.OBSTRUCTED, combat.updateReviveDetailed(second, 2, false, true));
        assertEquals(CombatRun.ReviveUpdate.NONE, combat.updateReviveDetailed(second, 2, false, true));
        assertTrue(combat.beginRevive(second, first, 2, true));
        advance(2000);
        assertEquals(CombatRun.ReviveUpdate.IN_PROGRESS, combat.updateReviveDetailed(second, 2, true, true));
        assertEquals(CombatRun.ReviveUpdate.OUT_OF_RANGE, combat.updateReviveDetailed(second, 4, true, true));
        assertTrue(combat.beginRevive(second, first, 2, true));
        assertEquals(CombatRun.ReviveUpdate.RELEASED, combat.updateReviveDetailed(second, 2, true, false));
        assertTrue(combat.beginRevive(second, first, 2, true));
        advance(3000);
        assertEquals(CombatRun.ReviveUpdate.REVIVED, combat.updateReviveDetailed(second, 2, true, true));
        assertEquals(CombatRun.ReviveUpdate.NONE, combat.updateReviveDetailed(second, 2, true, true));
        assertEquals(1, player(combat, second).stats().revives());
    }
    @Test void healingAtReloadDeadlinePreservesCompletedMagazineWithoutSnapshotTick() {
        var combat = combat();
        hit(combat, first);
        combat.fire(first, Optional.empty(), 1, true);
        combat.reload(first);
        advance(2000);
        assertTrue(combat.useMedkit(first));
        assertEquals(CombatRun.MAGAZINE, player(combat, first).ammunition());
    }
    @Test void healingBeforeReloadDeadlineStillInterruptsMagazineRefill() {
        var combat = combat();
        hit(combat, first);
        combat.fire(first, Optional.empty(), 1, true);
        combat.reload(first);
        advance(1999);
        assertTrue(combat.useMedkit(first));
        advance(1);
        assertEquals(CombatRun.MAGAZINE - 1, player(combat, first).ammunition());
    }
    @Test void receivedHealingDoesNotInterruptRecipientsReload() {
        var combat = combat();
        hit(combat, second);
        combat.fire(second, Optional.empty(), 1, true);
        combat.reload(second);
        advance(1000);
        assertTrue(combat.useMedkit(first, second, 2, true));
        assertEquals(1000, player(combat, second).reloadMillis());
        advance(1000);
        assertEquals(CombatRun.MAGAZINE, player(combat, second).ammunition());
    }
    @Test void medkitCanHealCrewButSpendsOnlyHelpersCharge() {
        var combat = combat();
        for (int i = 0; i < 6; i++) hit(combat, second);
        assertTrue(combat.useMedkit(first, second, 3, true));
        assertEquals(80, player(combat, second).health());
        assertEquals(100, player(combat, first).health());
        assertFalse(player(combat, first).medkitAvailable());
        assertTrue(player(combat, second).medkitAvailable());
        assertFalse(combat.useMedkit(first, second, 3, true));
        assertEquals(60, player(combat, second).stats().damageTaken());
    }
    @Test void invalidCrewHealingNeverConsumesCharge() {
        var combat = combat();
        assertFalse(combat.useMedkit(first, second, 1, true));
        hit(combat, second);
        for (double distance : List.of(-1d, 3.01d, Double.NaN, Double.POSITIVE_INFINITY))
            assertFalse(combat.useMedkit(first, second, distance, true));
        assertFalse(combat.useMedkit(first, second, 1, false));
        assertThrows(IllegalArgumentException.class, () -> combat.useMedkit(first, UUID.randomUUID(), 1, true));
        assertTrue(player(combat, first).medkitAvailable());
        down(combat, third);
        assertFalse(combat.useMedkit(first, third, 1, true));
        assertFalse(combat.useMedkit(third, second, 1, true));
        assertTrue(player(combat, third).medkitAvailable());
    }
    @Test void medkitIsPersonalSingleUseAndClampsHealth() {
        var combat = combat();
        assertFalse(combat.useMedkit(first));
        assertTrue(player(combat, first).medkitAvailable());
        hit(combat, first);
        assertTrue(combat.useMedkit(first));
        assertEquals(100, player(combat, first).health());
        hit(combat, first);
        assertFalse(combat.useMedkit(first));
        assertEquals(90, player(combat, first).health());
        assertTrue(player(combat, second).medkitAvailable());
        assertEquals(20, player(combat, first).stats().damageTaken());
        assertThrows(IllegalArgumentException.class, () -> combat.useMedkit(UUID.randomUUID()));
    }
    @Test void medkitCannotReviveButRemainsAvailableAfterTeammateRescue() {
        var combat = combat();
        down(combat, first);
        assertFalse(combat.useMedkit(first));
        assertTrue(combat.beginRevive(second, first, 2, true));
        advance(3000);
        assertTrue(combat.updateRevive(second, 2, true, true));
        assertTrue(combat.useMedkit(first));
        assertEquals(90, player(combat, first).health());
    }
    @Test void healingCancelsReloadAndReviveWithoutRestockingAmmo() {
        var combat = combat();
        down(combat, third);
        hit(combat, first);
        combat.fire(first, Optional.empty(), 1, true);
        combat.reload(first);
        assertTrue(combat.useMedkit(first));
        advance(3000);
        assertEquals(11, player(combat, first).ammunition());
        hit(combat, second);
        assertTrue(combat.beginRevive(second, third, 2, true));
        assertTrue(combat.useMedkit(second));
        advance(4000);
        assertFalse(combat.updateRevive(second, 2, true, true));
        assertTrue(player(combat, third).downed());
    }
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
