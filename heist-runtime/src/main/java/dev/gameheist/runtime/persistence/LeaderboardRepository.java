package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.player.*;
import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface LeaderboardRepository {
    CompletionStage<List<LeaderboardEntry>> leaderboard(StatisticsScope scope);
}
