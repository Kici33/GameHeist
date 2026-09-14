package dev.gameheist.runtime.health;

public record DrainStatus(boolean draining, int activeInstances, int pendingProfileOperations,
                          int unacknowledgedProfileWrites, boolean worldCleanupHealthy) {
    public DrainStatus {
        if (activeInstances < 0 || pendingProfileOperations < 0 || unacknowledgedProfileWrites < 0) {
            throw new IllegalArgumentException("Negative drain count");
        }
    }
    public boolean safeToStop() {
        return draining && activeInstances == 0 && pendingProfileOperations == 0
                && unacknowledgedProfileWrites == 0 && worldCleanupHealthy;
    }
}
