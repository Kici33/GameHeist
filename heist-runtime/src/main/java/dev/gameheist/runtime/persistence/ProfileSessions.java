package dev.gameheist.runtime.persistence;

import dev.gameheist.domain.player.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** Owner-thread cache. Futures are polled; repository callbacks never touch game state. */
public final class ProfileSessions {
    private final Thread owner = Thread.currentThread();
    private final ProfileRepository repository;
    private final LoadoutCatalog catalog;
    private final Map<UUID, Entry> sessions = new HashMap<>();
    private final Map<UUID, CompletableFuture<PlayerProfile>> writes = new HashMap<>();
    private final Map<UUID, PlayerProfile> unacknowledged = new HashMap<>();
    private final List<CompletableFuture<PlayerProfile>> operations = new ArrayList<>();
    private boolean draining;

    public ProfileSessions(ProfileRepository repository, LoadoutCatalog catalog) {
        this.repository = Objects.requireNonNull(repository);
        this.catalog = Objects.requireNonNull(catalog);
    }

    public void open(UUID player) {
        checkThread();
        if (draining) return;
        if (sessions.containsKey(player)) return;
        var entry = new Entry();
        sessions.put(player, entry);
        try {
            var write = writes.get(player);
            entry.pending = write == null ? repository.loadOrCreate(player).toCompletableFuture()
                    : write.handle((saved, failure) -> null).thenCompose(ignored -> repository.loadOrCreate(player));
            operations.add(entry.pending);
        }
        catch (RuntimeException failure) { entry.failed = true; }
    }

    public void close(UUID player) { checkThread(); sessions.remove(player); }

    public void tick() {
        checkThread();
        operations.removeIf(CompletableFuture::isDone);
        writes.entrySet().removeIf(item -> {
            if (!item.getValue().isDone()) return false;
            if (!item.getValue().isCompletedExceptionally() && !item.getValue().isCancelled()) unacknowledged.remove(item.getKey());
            return true;
        });
        sessions.forEach((id, entry) -> {
            if (entry.pending == null || !entry.pending.isDone()) return;
            try {
                var loaded = entry.pending.join();
                if (!loaded.playerId().equals(id)) throw new IllegalStateException("Profile identity mismatch");
                loaded.presets().forEach(catalog::validate);
                if (loaded.equals(unacknowledged.get(id))) unacknowledged.remove(id);
                entry.profile = loaded;
            } catch (RuntimeException failure) {
                // A write may have committed despite a transport failure. Reload before another edit.
                entry.profile = null;
                entry.failed = true;
            }
            entry.pending = null;
        });
    }

    public PlayerProfile require(UUID player) {
        checkThread();
        var entry = sessions.get(player);
        if (entry == null || entry.failed) throw new IllegalStateException("Profile unavailable; use /heist profile reload");
        if (entry.pending != null || entry.profile == null) throw new IllegalStateException("Profile operation pending; try again shortly");
        return entry.profile;
    }

    public boolean soundEnabled(UUID player) {
        checkThread();
        var entry = sessions.get(player);
        return entry == null || entry.profile == null || entry.profile.settings().soundEnabled();
    }

    public void reload(UUID player) {
        checkThread();
        if (draining) throw new IllegalStateException("Server is draining; profile reload is closed");
        var entry = sessions.get(player);
        if (entry != null && entry.pending != null) throw new IllegalStateException("Profile operation pending");
        close(player);
        open(player);
    }

    public void edit(UUID player, UnaryOperator<PlayerProfile> update) {
        checkThread();
        if (draining) throw new IllegalStateException("Server is draining; profile edits are closed");
        var current = require(player);
        var replacement = Objects.requireNonNull(update.apply(current));
        if (!replacement.playerId().equals(player) || current.revision() == Long.MAX_VALUE
                || replacement.revision() != current.revision() + 1) {
            throw new IllegalArgumentException("Invalid profile revision or identity");
        }
        replacement.presets().forEach(catalog::validate);
        var entry = sessions.get(player);
        unacknowledged.put(player, replacement);
        try {
            entry.pending = repository.save(replacement, current.revision())
                    .thenApply(ignored -> replacement).toCompletableFuture();
            writes.put(player, entry.pending);
            operations.add(entry.pending);
        } catch (RuntimeException failure) {
            entry.profile = null;
            entry.failed = true;
            throw new IllegalStateException("Profile save failed; reload before editing", failure);
        }
    }

    public void drain() { checkThread(); draining = true; }
    public int pendingOperations() {
        checkThread();
        return (int) operations.stream().filter(future -> !future.isDone()).count();
    }
    public int unacknowledgedWrites() { checkThread(); return unacknowledged.size(); }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Profile access must use the owner thread");
    }
    private static final class Entry {
        private PlayerProfile profile;
        private CompletableFuture<PlayerProfile> pending;
        private boolean failed;
    }
}
