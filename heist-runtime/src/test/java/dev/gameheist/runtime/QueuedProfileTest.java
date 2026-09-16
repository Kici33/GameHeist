package dev.gameheist.runtime;

import dev.gameheist.runtime.persistence.*;
import dev.gameheist.domain.player.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueuedProfileTest {
    @Test void queuedProfilesCannotEditOrReloadUntilReleased() {
        var sessions = new ProfileSessions(new InMemoryProfileRepository(), LoadoutCatalog.starter());
        UUID id = UUID.randomUUID(); sessions.open(id);
        assertThrows(IllegalStateException.class, () -> sessions.freeze(id));
        sessions.tick(); sessions.freeze(id);
        assertThrows(IllegalStateException.class, () -> sessions.edit(id, this::next));
        assertThrows(IllegalStateException.class, () -> sessions.reload(id));
        assertEquals(0, sessions.require(id).revision());
        sessions.unfreeze(id); sessions.edit(id, this::next); sessions.tick();
        assertEquals(1, sessions.require(id).revision());
        sessions.freeze(id); sessions.close(id); sessions.open(id); sessions.tick();
        sessions.edit(id, this::next); sessions.tick();
        assertEquals(2, sessions.require(id).revision());
    }
    private PlayerProfile next(PlayerProfile p) { return new PlayerProfile(p.playerId(), p.revision() + 1, p.presets(), p.selectedPreset(), p.settings()); }
}
