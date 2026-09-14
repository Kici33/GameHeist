package dev.gameheist.domain.objective;

import dev.gameheist.domain.arena.BlockPosition;
import java.util.*;

public record HeistSnapshot(boolean securityDisabled, boolean drillStarted, boolean drillComplete,
                            boolean jammed, int jamsTriggered, long drillRemainingMillis,
                            Optional<UUID> repairingPlayer, long repairRemainingMillis,
                            Map<UUID, BlockPosition> carriedBags, Set<BlockPosition> unavailableBags,
                            int securedBags, int requiredBags, int extractionVotes,
                            boolean extracting, long extractionRemainingMillis) {
    public HeistSnapshot {
        Objects.requireNonNull(repairingPlayer);
        carriedBags = Map.copyOf(carriedBags);
        unavailableBags = Set.copyOf(unavailableBags);
    }
}
