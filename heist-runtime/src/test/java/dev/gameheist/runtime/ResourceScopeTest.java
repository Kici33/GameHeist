package dev.gameheist.runtime;

import dev.gameheist.runtime.instance.ResourceScope;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ResourceScopeTest {
    @Test void releasesOtherResourcesWhenOneFailsAndRetriesOnlyFailures() throws IOException {
        var scope = new ResourceScope();
        var fail = new AtomicBoolean(true);
        List<String> calls = new ArrayList<>();
        scope.own(() -> calls.add("a"));
        scope.own(() -> { calls.add("b"); if (fail.getAndSet(false)) throw new IOException("retry"); });
        scope.own(() -> calls.add("c"));
        assertThrows(IOException.class, scope::release);
        assertEquals(List.of("c", "b", "a"), calls);
        assertThrows(IllegalStateException.class, () -> scope.own(() -> {}));
        scope.release();
        scope.release();
        assertEquals(List.of("c", "b", "a", "b"), calls);
    }
}
