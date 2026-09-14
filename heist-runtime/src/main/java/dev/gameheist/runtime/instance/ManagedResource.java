package dev.gameheist.runtime.instance;

import java.io.IOException;

@FunctionalInterface
public interface ManagedResource {
    void release() throws IOException;
}
