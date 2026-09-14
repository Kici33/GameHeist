package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.player.*;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface StatisticsRepository {
    CompletionStage<PlayerStatistics> statistics(UUID playerId, StatisticsScope scope);
}
