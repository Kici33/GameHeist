package dev.gameheist.paper.gameplay;

import java.util.OptionalLong;

/** One warning per scheduled wave, based on the authoritative pending-wave deadline. */
public final class WaveWarning {
    private boolean announced;
    private int wave = -1;
    public OptionalLong poll(int wavesSpawned, boolean active, OptionalLong remainingMillis) {
        if (wave != wavesSpawned) { wave = wavesSpawned; announced = false; }
        if (remainingMillis.isEmpty()) announced = false;
        return poll(active, remainingMillis);
    }
    public OptionalLong poll(boolean active, OptionalLong remainingMillis) {
        if (!active || announced || remainingMillis.isEmpty()) return OptionalLong.empty();
        long remaining = remainingMillis.getAsLong();
        if (remaining <= 0 || remaining > 5000) return OptionalLong.empty();
        announced = true;
        return OptionalLong.of((remaining + 999) / 1000);
    }
}
