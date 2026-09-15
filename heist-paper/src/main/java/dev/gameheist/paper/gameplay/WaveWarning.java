package dev.gameheist.paper.gameplay;

import java.util.OptionalLong;

/** One warning per match, based on the authoritative pending-wave deadline. */
public final class WaveWarning {
    private boolean announced;
    public OptionalLong poll(boolean active, OptionalLong remainingMillis) {
        if (!active || announced || remainingMillis.isEmpty()) return OptionalLong.empty();
        long remaining = remainingMillis.getAsLong();
        if (remaining <= 0 || remaining > 5000) return OptionalLong.empty();
        announced = true;
        return OptionalLong.of((remaining + 999) / 1000);
    }
}
