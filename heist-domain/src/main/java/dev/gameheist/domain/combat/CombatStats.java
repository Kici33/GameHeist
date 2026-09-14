package dev.gameheist.domain.combat;

public record CombatStats(int damageDealt, int damageTaken, int revives) {
    public CombatStats {
        if (damageDealt < 0 || damageTaken < 0 || revives < 0) throw new IllegalArgumentException("Negative combat contribution");
    }
}
