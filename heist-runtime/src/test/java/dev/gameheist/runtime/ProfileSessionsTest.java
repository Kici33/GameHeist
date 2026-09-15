package dev.gameheist.runtime;

import dev.gameheist.domain.player.*;
import dev.gameheist.runtime.persistence.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileSessionsTest {
    @Test void staleMenuRevisionCannotOverwriteNewerSavedPreferences() {
        ready();
        sessions.edit(player, 0, this::scout);
        assertEquals(ProfileSessions.Status.LOADING, sessions.status(player));
        repository.write.complete(null);
        sessions.tick();
        assertEquals(ProfileSessions.Status.READY, sessions.status(player));
        assertThrows(IllegalStateException.class, () -> sessions.edit(player, 0, this::scout));
        assertEquals(1, sessions.require(player).revision());
    }

    @Test void disconnectedMenuCannotEditOrReceiveOldLoadCompletion() {
        sessions.open(player);
        assertEquals(ProfileSessions.Status.LOADING, sessions.status(player));
        sessions.close(player);
        assertEquals(ProfileSessions.Status.FAILED, sessions.status(player));
        repository.loads.getFirst().complete(PlayerProfile.starter(player));
        sessions.tick();
        assertThrows(IllegalStateException.class, () -> sessions.edit(player, 0, this::scout));
        sessions.open(player);
        assertEquals(ProfileSessions.Status.LOADING, sessions.status(player));
    }

    @Test void failedSaveShowsFailureUntilExplicitReload() {
        ready();
        sessions.edit(player, 0, this::scout);
        repository.write.completeExceptionally(new IllegalStateException("offline"));
        sessions.tick();
        assertEquals(ProfileSessions.Status.FAILED, sessions.status(player));
        sessions.reload(player);
        assertEquals(ProfileSessions.Status.LOADING, sessions.status(player));
    }
    private final UUID player = UUID.randomUUID();
    private final DeferredRepository repository = new DeferredRepository();
    private final ProfileSessions sessions = new ProfileSessions(repository, LoadoutCatalog.starter());

    @Test void admissionWaitsForOwnerTickAndSaveAcknowledgement() {
        sessions.open(player);
        assertThrows(IllegalStateException.class, () -> sessions.require(player));
        repository.loads.getFirst().complete(PlayerProfile.starter(player));
        assertThrows(IllegalStateException.class, () -> sessions.require(player));
        sessions.tick();
        var captured = sessions.require(player).selectedLoadout();
        sessions.edit(player, this::scout);
        assertThrows(IllegalStateException.class, () -> sessions.require(player));
        repository.write.complete(null);
        sessions.tick();
        assertEquals(Role.SCOUT, sessions.require(player).selectedLoadout().role());
        assertEquals(Role.TECHNICIAN, captured.role());
    }
    @Test void oldConnectionLoadCannotReplaceNewConnection() {
        sessions.open(player);
        sessions.close(player);
        sessions.open(player);
        repository.loads.get(1).complete(scout(PlayerProfile.starter(player)));
        repository.loads.getFirst().complete(PlayerProfile.starter(player));
        sessions.tick();
        assertEquals(Role.SCOUT, sessions.require(player).selectedLoadout().role());
    }
    @Test void reconnectWaitsForOutstandingWriteThenReadsDatabaseAgain() {
        ready();
        sessions.edit(player, this::scout);
        sessions.close(player);
        sessions.open(player);
        assertEquals(1, repository.loads.size());
        repository.write.complete(null);
        assertEquals(2, repository.loads.size());
        repository.loads.get(1).complete(scout(PlayerProfile.starter(player)));
        sessions.tick();
        assertEquals(1, sessions.require(player).revision());
    }
    @Test void ambiguousSaveFailureInvalidatesCacheUntilReload() {
        ready();
        sessions.edit(player, this::scout);
        repository.write.completeExceptionally(new IllegalStateException("Network failure"));
        sessions.tick();
        assertThrows(IllegalStateException.class, () -> sessions.require(player));
        sessions.reload(player);
        repository.loads.getLast().complete(scout(PlayerProfile.starter(player)));
        sessions.tick();
        assertEquals(1, sessions.require(player).revision());
    }
    @Test void duplicateEditsAndReloadAreBlockedDuringWrite() {
        ready();
        sessions.edit(player, this::scout);
        assertThrows(IllegalStateException.class, () -> sessions.edit(player, this::scout));
        assertThrows(IllegalStateException.class, () -> sessions.reload(player));
    }
    @Test void unknownEquipmentAndWrongIdentityCannotEnterCache() {
        sessions.open(player);
        repository.loads.getFirst().complete(PlayerProfile.starter(UUID.randomUUID()));
        sessions.tick();
        assertThrows(IllegalStateException.class, () -> sessions.require(player));
        sessions.reload(player);
        repository.loads.getLast().complete(new PlayerProfile(player, 0,
                List.of(new Loadout(Role.SCOUT, "unknown", "medkit")), 0, PlayerSettings.defaults()));
        sessions.tick();
        assertThrows(IllegalStateException.class, () -> sessions.require(player));
    }
    @Test void soundPreferenceRemainsCommittedWhileSaving() {
        ready();
        sessions.edit(player, p -> new PlayerProfile(player, 1, p.presets(), 0, new PlayerSettings("en", false, true)));
        assertTrue(sessions.soundEnabled(player));
        repository.write.complete(null);
        sessions.tick();
        assertFalse(sessions.soundEnabled(player));
    }
    @Test void accessFromForeignThreadIsRejected() throws Exception {
        var future = CompletableFuture.runAsync(() -> sessions.open(player));
        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, future::get).getCause());
    }
    @Test void drainingWaitsForDisconnectedWritesAndRejectsNewOperations() {
        ready();
        sessions.edit(player, this::scout);
        sessions.close(player);
        sessions.drain();
        sessions.open(UUID.randomUUID());
        assertEquals(1, repository.loads.size());
        assertEquals(1, sessions.pendingOperations());
        assertEquals(1, sessions.unacknowledgedWrites());
        assertThrows(IllegalStateException.class, () -> sessions.edit(player, this::scout));
        assertThrows(IllegalStateException.class, () -> sessions.reload(player));
        repository.write.complete(null);
        sessions.tick();
        assertEquals(0, sessions.pendingOperations());
        assertEquals(0, sessions.unacknowledgedWrites());
    }
    @Test void drainingRetainsAmbiguousWritesEvenAfterDisconnect() {
        ready();
        sessions.edit(player, this::scout);
        sessions.close(player);
        sessions.drain();
        repository.write.completeExceptionally(new IllegalStateException("Acknowledgement lost"));
        sessions.tick();
        assertEquals(0, sessions.pendingOperations());
        assertEquals(1, sessions.unacknowledgedWrites());
    }
    @Test void detachedLoadsStillPreventPrematureDrainCompletion() {
        sessions.open(player);
        sessions.close(player);
        sessions.drain();
        assertEquals(1, sessions.pendingOperations());
        repository.loads.getFirst().complete(PlayerProfile.starter(player));
        sessions.tick();
        assertEquals(0, sessions.pendingOperations());
    }
    @Test void freshReadOfExactReplacementResolvesAmbiguousAcknowledgement() {
        ready();
        sessions.edit(player, this::scout);
        repository.write.completeExceptionally(new IllegalStateException("Acknowledgement lost"));
        sessions.tick();
        assertEquals(1, sessions.unacknowledgedWrites());
        sessions.reload(player);
        repository.loads.getLast().complete(scout(PlayerProfile.starter(player)));
        sessions.tick();
        assertEquals(0, sessions.unacknowledgedWrites());
    }
    private void ready() {
        sessions.open(player);
        repository.loads.getFirst().complete(PlayerProfile.starter(player));
        sessions.tick();
    }
    private PlayerProfile scout(PlayerProfile current) {
        return new PlayerProfile(player, current.revision() + 1, List.of(Loadout.starter(Role.SCOUT)), 0, current.settings());
    }
    private static final class DeferredRepository implements ProfileRepository {
        private final List<CompletableFuture<PlayerProfile>> loads = new ArrayList<>();
        private final CompletableFuture<Void> write = new CompletableFuture<>();
        @Override public CompletionStage<PlayerProfile> loadOrCreate(UUID player) {
            var result = new CompletableFuture<PlayerProfile>();
            loads.add(result);
            return result;
        }
        @Override public CompletionStage<Void> save(PlayerProfile profile, long expected) { return write; }
    }
}
