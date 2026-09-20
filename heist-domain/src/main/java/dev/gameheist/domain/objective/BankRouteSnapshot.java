package dev.gameheist.domain.objective;

import dev.gameheist.domain.arena.BlockPosition;
import java.util.*;

public record BankRouteSnapshot(boolean keycardCollected, Set<BlockPosition> disabledCameras,
                                int cameraCount, boolean powerRestored, Optional<UUID> restoringPlayer,
                                long powerRemainingMillis, boolean escapeActivated,
                                Set<UUID> outsideExtraction) {
    public BankRouteSnapshot {
        disabledCameras = Set.copyOf(disabledCameras);
        Objects.requireNonNull(restoringPlayer);
        outsideExtraction = Set.copyOf(outsideExtraction);
    }
    public boolean camerasDisabled() { return disabledCameras.size() == cameraCount; }
}
