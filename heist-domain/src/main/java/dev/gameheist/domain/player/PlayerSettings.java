package dev.gameheist.domain.player;

import java.util.Objects;

public record PlayerSettings(String language, boolean soundEnabled, boolean reducedParticles,
                             boolean reducedMotion, boolean notificationsEnabled) {
    public PlayerSettings(String language, boolean soundEnabled, boolean reducedParticles) {
        this(language, soundEnabled, reducedParticles, false, true);
    }
    public PlayerSettings {
        if (!java.util.Set.of("en", "pl").contains(Objects.requireNonNull(language))) throw new IllegalArgumentException("Language must be en or pl");
    }
    public PlayerSettings change(String key, String value) {
        if (key.equals("language")) return new PlayerSettings(value, soundEnabled, reducedParticles, reducedMotion, notificationsEnabled);
        if (!java.util.Set.of("on", "off").contains(value)) throw new IllegalArgumentException("Value must be on or off");
        boolean enabled = value.equals("on");
        return switch (key) {
            case "sound" -> new PlayerSettings(language, enabled, reducedParticles, reducedMotion, notificationsEnabled);
            case "particles" -> new PlayerSettings(language, soundEnabled, !enabled, reducedMotion, notificationsEnabled);
            case "motion" -> new PlayerSettings(language, soundEnabled, reducedParticles, !enabled, notificationsEnabled);
            case "notifications" -> new PlayerSettings(language, soundEnabled, reducedParticles, reducedMotion, enabled);
            default -> throw new IllegalArgumentException("Unknown preference");
        };
    }
    public static PlayerSettings defaults() { return new PlayerSettings("en", true, false); }
}
