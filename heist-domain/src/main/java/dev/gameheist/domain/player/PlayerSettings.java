package dev.gameheist.domain.player;

import java.util.Objects;

public record PlayerSettings(String language, boolean soundEnabled, boolean reducedParticles) {
    public PlayerSettings {
        if (Objects.requireNonNull(language).isBlank()) throw new IllegalArgumentException("Blank language");
    }
    public static PlayerSettings defaults() { return new PlayerSettings("en", true, false); }
}
