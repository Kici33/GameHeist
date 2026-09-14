package dev.gameheist.runtime.instance;

import java.io.IOException;
import java.util.*;

/** Releases in reverse registration order. Failed releases remain owned for a later retry. */
public final class ResourceScope {
    private final List<ManagedResource> resources = new ArrayList<>();
    private boolean closing;

    public void own(ManagedResource resource) {
        if (closing) throw new IllegalStateException("Scope is closing");
        resources.add(Objects.requireNonNull(resource));
    }

    public void release() throws IOException {
        closing = true;
        IOException failure = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).release();
                resources.remove(i);
            } catch (Exception exception) {
                if (failure == null) failure = new IOException("Instance resource cleanup failed");
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }
}
