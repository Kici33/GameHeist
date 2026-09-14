package dev.gameheist.runtime.health;

import dev.gameheist.runtime.instance.InstanceManager;
import dev.gameheist.runtime.persistence.ProfileSessions;
import java.util.function.BooleanSupplier;

/** Called only by the owner thread; HTTP may request draining but cannot mutate these managers. */
public final class DrainController {
    private final InstanceManager instances;
    private final ProfileSessions profiles;
    private final BooleanSupplier worldCleanupHealthy;
    public DrainController(InstanceManager instances, ProfileSessions profiles, BooleanSupplier worldCleanupHealthy) {
        this.instances = instances;
        this.profiles = profiles;
        this.worldCleanupHealthy = worldCleanupHealthy;
    }
    public void drain() {
        instances.drain();
        profiles.drain();
    }
    public DrainStatus status() {
        return new DrainStatus(instances.draining(), instances.all().size(), profiles.pendingOperations(),
                profiles.unacknowledgedWrites(), worldCleanupHealthy.getAsBoolean());
    }
}
