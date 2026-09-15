package dev.gameheist.domain.player;

import java.util.UUID;
import java.util.Objects;

/** Fastest acknowledged production win within one exact arena/difficulty/crew-size scope. */
public record LeaderboardEntry(UUID playerId, long wins, long bestWinMillis, long gameplayMillis) {
    public LeaderboardEntry {
        Objects.requireNonNull(playerId);
        if (wins < 1 || bestWinMillis < 0 || gameplayMillis < bestWinMillis) throw new IllegalArgumentException("Invalid leaderboard row");
    }
}
