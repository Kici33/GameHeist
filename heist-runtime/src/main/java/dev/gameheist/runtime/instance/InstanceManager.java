package dev.gameheist.runtime.instance;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.arena.BlockPosition;
import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.objective.HeistSnapshot;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.player.*;
import dev.gameheist.runtime.arena.ArenaRegistry;
import dev.gameheist.runtime.persistence.ResultRepository;
import dev.gameheist.runtime.persistence.ReservationRepository;
import dev.gameheist.domain.network.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Owns all mutations on its construction thread. Defaults to one slot at the application boundary. */
public final class InstanceManager {
    private final Thread owner = Thread.currentThread();
    private final ArenaRegistry arenas;
    private final WorldGateway worlds;
    private final ResultRepository results;
    private final Clock clock;
    private final LoadoutCatalog catalog;
    private final int capacity;
    private final Map<UUID, Entry> instances = new LinkedHashMap<>();
    private final Map<UUID, UUID> playerInstances = new HashMap<>();
    private boolean draining;

    public InstanceManager(ArenaRegistry arenas, WorldGateway worlds, ResultRepository results,
                           Clock clock, LoadoutCatalog catalog, int capacity) {
        this.arenas = Objects.requireNonNull(arenas);
        this.worlds = Objects.requireNonNull(worlds);
        this.results = Objects.requireNonNull(results);
        this.clock = Objects.requireNonNull(clock);
        this.catalog = Objects.requireNonNull(catalog);
        if (capacity < 1) throw new IllegalArgumentException("Instance capacity must be positive");
        this.capacity = capacity;
    }

    public UUID createPractice(ArenaKey arenaKey, Difficulty difficulty, long seed) throws IOException {
        checkThread();
        if (draining) throw new IllegalStateException("Server is draining");
        if (instances.size() >= capacity) throw new IllegalStateException("No instance capacity");
        var arena = arenas.require(arenaKey);
        UUID id = UUID.randomUUID();
        var match = new Match(id, arena, difficulty, seed, true, clock, catalog);
        var world = worlds.create(id, arena);
        instances.put(id, new Entry(match, world));
        return id;
    }

    public void join(UUID instanceId, UUID playerId, Loadout loadout) {
        checkThread();
        if (require(instanceId).reservation != null) throw new IllegalStateException("This instance requires reserved admission");
        joinInternal(instanceId, playerId, loadout);
    }
    private void joinInternal(UUID instanceId, UUID playerId, Loadout loadout) {
        if (draining) throw new IllegalStateException("Server is draining");
        if (playerInstances.containsKey(playerId)) throw new IllegalStateException("Player already belongs to an instance");
        require(instanceId).match.join(playerId, loadout);
        playerInstances.put(playerId, instanceId);
    }

    /** Bind only after trusted allocation and assignment acknowledgement, before transferring any players. */
    public void bindReservation(ReservationSnapshot snapshot, BackendAssignment localTarget, ReservationRepository repository) {
        checkThread();
        if (draining || !snapshot.active() || !snapshot.request().expiresAt().isAfter(clock.instant())
                || !snapshot.assignment().equals(Optional.of(localTarget))) throw new IllegalStateException("Invalid reservation binding");
        var entry = require(localTarget.matchId());
        if (entry.reservation != null) {
            if (entry.reservation.request.equals(snapshot.request()) && entry.reservation.target.equals(localTarget)) return;
            throw new IllegalStateException("Instance already bound to another reservation");
        }
        if (entry.match.phase() != MatchPhase.BRIEFING || entry.match.participantCount() != 0
                || !entry.match.snapshot().arena().equals(snapshot.request().arena())
                || entry.match.difficulty() != snapshot.request().difficulty()
                || snapshot.request().crew().size() > arenas.require(snapshot.request().arena()).capacity()) {
            throw new IllegalStateException("Reservation does not match an empty prepared instance");
        }
        snapshot.request().crew().values().forEach(catalog::validate);
        entry.reservation = new ReservationBinding(snapshot.request(), localTarget, Objects.requireNonNull(repository));
    }

    /** Called on the owner thread after atomic admission consumption and pack readiness in the adapter. */
    public void joinReserved(UUID playerId, UUID attemptId, ReservationSnapshot accepted) {
        checkThread();
        var target = accepted.assignment().orElseThrow(() -> new IllegalStateException("Unassigned reservation"));
        var binding = require(target.matchId()).reservation;
        if (binding == null || !binding.target.equals(target) || !binding.request.equals(accepted.request())
                || !accepted.active() || !accepted.request().expiresAt().isAfter(clock.instant())
                || !attemptId.equals(accepted.admissionAttempts().get(playerId))) {
            throw new IllegalStateException("Stale or unconsumed reserved admission");
        }
        joinInternal(target.matchId(), playerId, accepted.request().crew().get(playerId));
    }

    public void leaveBriefing(UUID playerId) {
        checkThread();
        UUID instanceId = playerInstances.get(playerId);
        if (instanceId == null) throw new IllegalArgumentException("Player is not in an instance");
        require(instanceId).match.leaveBriefing(playerId);
        playerInstances.remove(playerId);
    }

    public void start(UUID id) {
        checkThread();
        var entry = require(id);
        if (entry.reservation != null && (!entry.reservation.request.expiresAt().isAfter(clock.instant())
                || !entry.match.snapshot().participants().keySet().equals(entry.reservation.request.crew().keySet()))) {
            throw new IllegalStateException("Reserved crew must arrive before the admission deadline");
        }
        entry.match.start();
    }
    public void raiseAlarm(UUID id) { checkThread(); require(id).match.raiseAlarm(); }
    public boolean completeObjective(UUID id, String objective) {
        checkThread();
        if (require(id).match.heistSnapshot().isPresent()) {
            throw new IllegalStateException("Physical heists must be completed through their world interactions");
        }
        return require(id).match.completeObjective(objective);
    }
    public String interact(UUID playerId, BlockPosition block, Position position) {
        checkThread();
        UUID id = playerInstances.get(playerId);
        if (id == null) throw new IllegalStateException("Player is not in an instance");
        return require(id).match.interact(playerId, block, position);
    }
    public Optional<HeistSnapshot> heistSnapshot(UUID id) {
        checkThread();
        return require(id).match.heistSnapshot();
    }
    public void updateHeist(UUID id, Map<UUID, Position> presentPlayers) {
        checkThread();
        require(id).match.updateHeist(presentPlayers);
    }
    public void stop(UUID id, String reason) {
        checkThread();
        require(id).match.abort(reason);
        tick();
    }
    public void own(UUID id, ManagedResource resource) {
        checkThread();
        require(id).scope.own(resource);
    }
    public Optional<UUID> instanceOf(UUID playerId) {
        checkThread();
        return Optional.ofNullable(playerInstances.get(playerId));
    }
    public InstanceSnapshot snapshot(UUID id) { checkThread(); return view(require(id)); }
    public List<InstanceSnapshot> all() {
        checkThread();
        return instances.values().stream().map(this::view).toList();
    }
    public void drain() { checkThread(); draining = true; }
    public boolean draining() { checkThread(); return draining; }

    public void tick() {
        checkThread();
        for (var item : List.copyOf(instances.entrySet())) {
            Entry entry = item.getValue();
            if (entry.reservation != null && entry.match.phase() == MatchPhase.BRIEFING
                    && !entry.reservation.request.expiresAt().isAfter(clock.instant())) entry.match.abort("reservation_expired");
            entry.match.tick();
            if (entry.match.result().isEmpty() || clock.instant().isBefore(entry.retryAt)) continue;
            try {
                if (!entry.saved) {
                    if (entry.save == null) {
                        entry.save = Objects.requireNonNull(results.save(entry.match.result().orElseThrow()))
                                .toCompletableFuture();
                    }
                    if (!entry.save.isDone()) continue;
                    entry.save.join();
                    entry.saved = true;
                }
                // Stop effects/tasks before evacuating players and unloading their world.
                entry.scope.release();
                entry.world.release();
                if (entry.reservation != null && !entry.reservation.release()) continue;
                entry.match.close();
                playerInstances.values().removeIf(item.getKey()::equals);
                instances.remove(item.getKey());
            } catch (Exception failure) {
                entry.error = failure.toString();
                if (!entry.saved) entry.save = null;
                entry.retryAt = clock.instant().plusSeconds(5);
            }
        }
    }

    /** Shutdown cannot wait indefinitely for external persistence. Reports every unresolved release. */
    public List<String> shutdown() {
        checkThread();
        drain();
        for (Entry entry : instances.values()) entry.match.abort("server_shutdown");
        tick();
        List<String> failures = new ArrayList<>();
        for (var item : List.copyOf(instances.entrySet())) {
            Entry entry = item.getValue();
            if (!entry.saved) failures.add(item.getKey() + ": result not acknowledged before shutdown");
            try {
                entry.scope.release();
                entry.world.release();
                if (entry.reservation != null && (!entry.saved || !entry.reservation.release())) {
                    failures.add(item.getKey() + ": reservation release not acknowledged before shutdown");
                }
                instances.remove(item.getKey());
                playerInstances.values().removeIf(item.getKey()::equals);
            } catch (Exception failure) {
                  failures.add(item.getKey() + ": cleanup failed: " + failure);
            }
        }
        return List.copyOf(failures);
    }

    private InstanceSnapshot view(Entry entry) {
        var match = entry.match.snapshot();
        InstanceState state = entry.saved ? InstanceState.CLOSING
                : match.result().isPresent() ? InstanceState.FINALIZING
                : match.phase() == MatchPhase.BRIEFING ? InstanceState.READY : InstanceState.RUNNING;
        return new InstanceSnapshot(match, entry.world.name(), state, Optional.ofNullable(entry.error));
    }
    private Entry require(UUID id) {
        var entry = instances.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown instance: " + id);
        return entry;
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("InstanceManager accessed off owner thread");
    }
    private static final class Entry {
        private final Match match;
        private final WorldInstance world;
        private final ResourceScope scope = new ResourceScope();
        private CompletableFuture<Void> save;
        private boolean saved;
        private String error;
        private Instant retryAt = Instant.MIN;
        private ReservationBinding reservation;
        private Entry(Match match, WorldInstance world) { this.match = match; this.world = world; }
    }
    private static final class ReservationBinding {
        private final CrewReservation request;
        private final BackendAssignment target;
        private final ReservationRepository repository;
        private CompletableFuture<ReservationSnapshot> release;
        private ReservationBinding(CrewReservation request, BackendAssignment target, ReservationRepository repository) {
            this.request = request;
            this.target = target;
            this.repository = repository;
        }
        private boolean release() {
            if (release == null) release = repository.release(request.id(), target).toCompletableFuture();
            if (!release.isDone()) return false;
            try {
                var result = release.join();
                if (result.active() || !result.request().equals(request) || !result.assignment().equals(Optional.of(target))) {
                    throw new IllegalStateException("Reservation release was not acknowledged");
                }
                return true;
            } catch (RuntimeException failure) { release = null; throw failure; }
        }
    }
}
