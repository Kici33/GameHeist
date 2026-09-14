package dev.gameheist.domain.combat;

import dev.gameheist.domain.player.*;
import java.time.*;
import java.util.*;

/** Match-thread combat rules. Visibility and distances must come from the trusted world adapter. */
public final class CombatRun {
    public static final int MAGAZINE = 12, GUARD_HEALTH = 60, SHOT_DAMAGE = 20;
    public static final double SHOT_RANGE = 24, GUARD_RANGE = 16, REVIVE_RANGE = 3;
    public enum Attack { NONE, AIMING, HIT }
    private final Clock clock;
    private final Map<UUID, Fighter> players = new LinkedHashMap<>();
    private final Map<String, Guard> guards = new LinkedHashMap<>();
    private Instant waveAt;
    private boolean waveSpawned;

    public CombatRun(Map<UUID, Loadout> crew, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        if (crew.isEmpty() || crew.size() > 4) throw new IllegalArgumentException("Combat needs 1-4 players");
        crew.forEach((id, loadout) -> players.put(id, new Fighter(loadout.role())));
    }
    public void registerGuard(String id) {
        if (guards.containsKey(id) || guards.size() >= 24) throw new IllegalStateException("Duplicate guard or combat cap exceeded");
        guards.put(Objects.requireNonNull(id), new Guard());
    }
    public boolean active(UUID id) { return players.containsKey(id) && players.get(id).health > 0; }
    public boolean defeated() { return players.values().stream().noneMatch(p -> p.health > 0); }
    public boolean canFire(UUID id) {
        Fighter player = require(id);
        tickReload(player);
        return player.health > 0 && player.reloadAt == null && player.ammunition > 0 && !clock.instant().isBefore(player.nextShot);
    }
    public boolean fire(UUID player, Optional<String> hit, double distance, boolean clear) {
        Fighter shooter = require(player);
        if (!canFire(player)) return false;
        Guard guard = hit.map(guards::get).orElse(null);
        shooter.ammunition--;
        shooter.nextShot = clock.instant().plusMillis(300);
        cancelRevive(shooter);
        if (guard != null && guard.health > 0 && clear && inRange(distance, SHOT_RANGE)) {
            int damage = Math.min(SHOT_DAMAGE, guard.health);
            guard.health -= damage;
            shooter.dealt += damage;
            if (guard.health == 0) guard.target = null;
        }
        return true;
    }
    public boolean reload(UUID id) {
        Fighter player = require(id);
        tickReload(player);
        if (player.health == 0 || player.reloadAt != null || player.ammunition == MAGAZINE) return false;
        player.reloadAt = clock.instant().plusSeconds(2);
        cancelRevive(player);
        return true;
    }
    /** No catch-up burst: each attack requires a fresh uninterrupted aim and cooldown. */
    public Attack attack(String id, Optional<UUID> candidate, double distance, boolean clear, boolean loud) {
        Guard guard = guards.get(id);
        if (guard == null) throw new IllegalArgumentException("Unknown combat guard");
        UUID target = candidate.filter(this::active).orElse(null);
        if (!loud || guard.health == 0 || target == null || !clear || !inRange(distance, GUARD_RANGE)) {
            guard.target = null;
            return Attack.NONE;
        }
        Instant now = clock.instant();
        if (now.isBefore(guard.nextAttack)) return Attack.NONE;
        if (!target.equals(guard.target)) {
            guard.target = target;
            guard.aimAt = now.plusSeconds(1);
            return Attack.AIMING;
        }
        if (now.isBefore(guard.aimAt)) return Attack.NONE;
        guard.target = null;
        guard.nextAttack = now.plusMillis(2500);
        Fighter victim = require(target);
        int damage = Math.min(players.size() == 1 ? 6 : 10, victim.health);
        victim.health -= damage;
        victim.taken += damage;
        cancelRevive(victim);
        if (victim.health == 0) victim.reloadAt = null;
        return Attack.HIT;
    }
    public boolean beginRevive(UUID rescuer, UUID target, double distance, boolean clear) {
        Fighter helper = require(rescuer), downed = require(target);
        if (rescuer.equals(target) || helper.health == 0 || downed.health != 0
                || !clear || !inRange(distance, REVIVE_RANGE)) return false;
        if (target.equals(helper.reviving)) return false;
        helper.reviving = target;
        helper.reviveAt = clock.instant().plusSeconds(helper.role == Role.SUPPORT ? 3 : 4);
        helper.reloadAt = null;
        return true;
    }
    /** Called every frame, including invalid/absent helpers, to prevent progress through cover or disconnects. */
    public boolean updateRevive(UUID rescuer, double distance, boolean clear, boolean holding) {
        Fighter helper = require(rescuer);
        if (helper.reviving == null) return false;
        Fighter target = require(helper.reviving);
        if (helper.health == 0 || target.health != 0 || !holding || !clear || !inRange(distance, REVIVE_RANGE)) {
            cancelRevive(helper);
            return false;
        }
        if (clock.instant().isBefore(helper.reviveAt)) return false;
        target.health = 50;
        helper.revives++;
        cancelRevive(helper);
        return true;
    }
    /** One wave per match, fifteen seconds after the first loud frame. */
    public boolean claimWave(boolean loud) {
        if (!loud || waveSpawned) return false;
        if (waveAt == null) waveAt = clock.instant().plusSeconds(15);
        if (clock.instant().isBefore(waveAt)) return false;
        waveSpawned = true;
        return true;
    }
    public CombatSnapshot snapshot() {
        Map<UUID, CombatSnapshot.Player> crew = new LinkedHashMap<>();
        players.forEach((id, p) -> {
            tickReload(p);
            crew.put(id, new CombatSnapshot.Player(p.health, p.ammunition, remaining(p.reloadAt),
                    Optional.ofNullable(p.reviving), remaining(p.reviveAt), new CombatStats(p.dealt, p.taken, p.revives)));
        });
        Map<String, Integer> enemies = new LinkedHashMap<>();
        guards.forEach((id, g) -> enemies.put(id, g.health));
        return new CombatSnapshot(crew, enemies, waveSpawned);
    }
    public Map<UUID, CombatStats> contributions() {
        Map<UUID, CombatStats> stats = new HashMap<>();
        players.forEach((id, p) -> stats.put(id, new CombatStats(p.dealt, p.taken, p.revives)));
        return Map.copyOf(stats);
    }
    private Fighter require(UUID id) {
        var player = players.get(id);
        if (player == null) throw new IllegalArgumentException("Player is not in this combat crew");
        return player;
    }
    private void tickReload(Fighter p) {
        if (p.reloadAt != null && !clock.instant().isBefore(p.reloadAt)) {
            p.ammunition = MAGAZINE;
            p.reloadAt = null;
        }
    }
    private long remaining(Instant time) { return time == null ? 0 : Math.max(0, Duration.between(clock.instant(), time).toMillis()); }
    private static boolean inRange(double distance, double maximum) { return Double.isFinite(distance) && distance >= 0 && distance <= maximum; }
    private static void cancelRevive(Fighter p) { p.reviving = null; p.reviveAt = null; }
    private static final class Fighter {
        private final Role role;
        private int health = 100, ammunition = MAGAZINE, dealt, taken, revives;
        private Instant nextShot = Instant.MIN, reloadAt, reviveAt;
        private UUID reviving;
        private Fighter(Role role) { this.role = role; }
    }
    private static final class Guard {
        private int health = GUARD_HEALTH;
        private UUID target;
        private Instant aimAt, nextAttack = Instant.MIN;
    }
}
