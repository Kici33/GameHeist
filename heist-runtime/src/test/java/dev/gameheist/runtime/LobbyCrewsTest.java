package dev.gameheist.runtime;

import dev.gameheist.runtime.network.LobbyCrews;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyCrewsTest {
    private final UUID leader = UUID.randomUUID(), member = UUID.randomUUID();
    @Test void invitationRequiresConsentAndQueueFreezesMembership() {
        var crews = new LobbyCrews(Clock.systemUTC());
        crews.invite(leader, member);
        assertEquals(Set.of(leader), crews.lock(leader));
        assertThrows(IllegalStateException.class, () -> crews.accept(member));
        crews.unlock(leader); crews.invite(leader, member); crews.accept(member);
        assertThrows(IllegalStateException.class, () -> crews.lock(member));
        var snapshot = crews.lock(leader);
        assertEquals(Set.of(leader, member), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(IllegalStateException.class, () -> crews.leave(member));
        crews.unlock(member); crews.leave(member);
        assertEquals(Set.of(leader), crews.lock(leader));
    }
    @Test void crewCapacityAndDisconnectDissolveTheOldParty() {
        var crews = new LobbyCrews(Clock.systemUTC());
        for (int i = 0; i < 3; i++) { var id = i == 0 ? member : UUID.randomUUID(); crews.invite(leader, id); crews.accept(id); }
        assertThrows(IllegalStateException.class, () -> crews.invite(leader, UUID.randomUUID()));
        crews.lock(leader); crews.forget(member);
        assertEquals(Set.of(leader), crews.lock(leader));
        assertEquals(Set.of(member), crews.lock(member));
    }
    @Test void oldLeaderInvitationCannotReviveADissolvedParty() {
        var crews = new LobbyCrews(Clock.systemUTC());
        crews.invite(leader, member); crews.leave(leader);
        assertThrows(IllegalStateException.class, () -> crews.accept(member));
    }
    @Test void invitationExpiresAtThirtySeconds() {
        var current = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-09-16T00:00:00Z"));
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return current.get(); }
        };
        var crews = new LobbyCrews(clock); crews.invite(leader, member);
        current.set(current.get().plusSeconds(30));
        assertThrows(IllegalStateException.class, () -> crews.accept(member));
    }
}
