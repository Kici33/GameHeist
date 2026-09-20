package dev.gameheist.paper.network;

import dev.gameheist.domain.arena.ArenaKey;
import dev.gameheist.domain.match.*;
import dev.gameheist.domain.match.Difficulty;
import dev.gameheist.domain.network.*;
import dev.gameheist.domain.player.Loadout;
import dev.gameheist.mongo.*;
import dev.gameheist.paper.pack.ResourcePackGate;
import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.runtime.instance.InstanceManager;
import dev.gameheist.runtime.persistence.ProfileSessions;
import dev.gameheist.runtime.network.LobbyCrews;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Optional practice-network adapter. Mongo acknowledgements are polled only on Paper's owner thread. */
public final class NetworkController implements Listener, CommandExecutor {
    private final JavaPlugin plugin;
    private final MongoStore store;
    private final ProfileSessions profiles;
    private final InstanceManager instances;
    private final ResourcePackGate packs;
    private final PlayerSessions players;
    private final String role, server;
    private final UUID incarnation = UUID.randomUUID();
    private final ArenaKey arena;
    private final Difficulty difficulty;
    private final LobbyCrews crews = new LobbyCrews(Clock.systemUTC());
    private final BooleanSupplier allocationGate;
    private final boolean oneShot;
    private final Map<UUID, Connection> connections = new HashMap<>();
    private final List<QueuedCrew> queued = new ArrayList<>();
    private CompletableFuture<Void> heartbeat;
    private long nextHeartbeat, lastLease, heartbeatStarted, started = System.nanoTime();
    private boolean failed;
    private BackendAssignment prepared;
    private CompletableFuture<Optional<ReservationSnapshot>> allocation;
    private CompletableFuture<Void> publication;
    private CompletableFuture<Optional<String>> evacuation;
    private long evacuationDeadline;
    private boolean evacuationSent;
    private ReservationSnapshot binding;
    private long nextAllocation;

    public NetworkController(JavaPlugin plugin, MongoStore store, ProfileSessions profiles, InstanceManager instances,
                             ResourcePackGate packs, PlayerSessions players, BooleanSupplier allocationGate, boolean oneShot) {
        this.plugin = plugin; this.store = Objects.requireNonNull(store); this.profiles = profiles;
        this.instances = instances; this.packs = packs; this.players = players;
        this.allocationGate = Objects.requireNonNull(allocationGate); this.oneShot = oneShot;
        role = plugin.getConfig().getString("network.role", "disabled");
        server = System.getenv().getOrDefault("HEIST_SERVER_ID", Objects.requireNonNull(plugin.getConfig().getString("network.server-id")));
        if (!Set.of("lobby", "backend").contains(role)) throw new IllegalArgumentException("Network role must be lobby or backend");
        new BackendAssignment(server, incarnation, UUID.randomUUID(), 1);
        arena = new ArenaKey(plugin.getConfig().getString("network.arena", "graybox"), plugin.getConfig().getInt("network.arena-version", 4));
        difficulty = Difficulty.valueOf(plugin.getConfig().getString("network.difficulty", "NORMAL"));
        if (role.equals("backend") && plugin.getConfig().getInt("instances.maximum") != 1)
            throw new IllegalArgumentException("Network backend requires exactly one instance slot");
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        Objects.requireNonNull(plugin.getCommand("heistnet")).setExecutor(this);
    }

    public void joined(Player player) {
        if (connections.size() >= 128 || failed) { player.kick(Component.text("Network service unavailable; retry through the proxy.")); return; }
        connections.put(player.getUniqueId(), new Connection(player));
    }
    public boolean accepting() {
        return !failed && lastLease != 0 && !instances.draining()
                && (role.equals("lobby") || (prepared != null && binding == null));
    }
    @EventHandler public void join(PlayerJoinEvent event) { joined(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        connections.remove(id); profiles.close(id); crews.forget(id);
    }

    public void tick() {
        long now = System.nanoTime();
        if (failed) { evacuate(now); return; }
        // Check BEFORE consuming acknowledgements: an old reply must never revive a stalled process.
        if (instances.draining() || now - (lastLease == 0 ? started : lastLease) > 15_000_000_000L) {
            failNetwork("network_lease_lost_or_draining"); return;
        }
        if (heartbeat != null && heartbeat.isDone()) {
            try { heartbeat.join(); lastLease = heartbeatStarted; }
            catch (RuntimeException failure) { plugin.getLogger().warning("Network heartbeat failed; admission remains fenced by its lease."); }
            heartbeat = null; nextHeartbeat = now + 5_000_000_000L;
        }
        if (heartbeat == null && now >= nextHeartbeat) {
            heartbeatStarted = now;
            heartbeat = store.network().heartbeat(server, incarnation, role).toCompletableFuture();
        }
        if (lastLease == 0) return;
        reconcileQueues();
        if (role.equals("backend")) backend(now);
        if (failed) return;
        for (var connection : List.copyOf(connections.values())) {
            if (!current(connection)) continue;
            if (connection.queuePending) continue;
            if (connection.pending != null) {
                if (connection.pending.isDone()) {
                    var completion = connection.completion;
                    connection.pending = null; connection.completion = null;
                    try { completion.run(); }
                    catch (RuntimeException failure) { say(connection.player, "Network operation not confirmed; checking durable state before retry."); }
                    connection.nextQuery = now + 2_000_000_000L;
                }
                continue;
            }
            if (connection.admitted) {
                if (instances.instanceOf(connection.player.getUniqueId()).isEmpty()) returnToLobby(connection);
                continue;
            }
            if (connection.accepted != null && instances.instanceOf(connection.player.getUniqueId()).isEmpty()) {
                if (profiles.status(connection.player.getUniqueId()) == ProfileSessions.Status.FAILED) { returnToLobby(connection); continue; }
                if (profiles.status(connection.player.getUniqueId()) == ProfileSessions.Status.READY && packs.ready(connection.player)) admitLocally(connection);
                continue;
            }
            if (now < connection.nextQuery) continue;
            connection.nextQuery = now + 2_000_000_000L;
            long epoch = connection.routeEpoch;
            later(connection, store.network().route(connection.player.getUniqueId()), route -> {
                if (epoch == connection.routeEpoch) route(connection, route);
            });
        }
    }

    private void backend(long now) {
        try {
            if (prepared != null && instances.all().stream().noneMatch(i -> i.match().id().equals(prepared.matchId()))) {
                if (allocation != null || publication != null) throw new IllegalStateException("Prepared world lost with allocation in flight");
                if (oneShot && binding != null) {
                    instances.drain(); profiles.drain();
                    prepared = null; binding = null;
                    return;
                }
                prepared = null; binding = null; publication = null; allocation = null;
            }
            if (prepared == null) prepared = new BackendAssignment(server, incarnation, instances.createPractice(arena, difficulty, new Random().nextLong()), 1);
            if (publication != null && publication.isDone()) { publication.join(); publication = null; }
            if (allocation != null && allocation.isDone()) {
                var assigned = allocation.join(); allocation = null;
                if (assigned.isPresent()) {
                    binding = assigned.orElseThrow();
                    instances.bindReservation(binding, prepared, store.reservations());
                    publication = store.network().ready(binding.request().id(), prepared).toCompletableFuture();
                }
            }
            if (binding == null && allocation == null && allocationGate.getAsBoolean() && now >= nextAllocation) {
                allocation = store.network().allocate(prepared, arena, difficulty).toCompletableFuture();
                nextAllocation = now + 2_000_000_000L;
            }
            if (binding != null && instances.snapshot(prepared.matchId()).match().phase() == MatchPhase.BRIEFING
                    && instances.snapshot(prepared.matchId()).match().participants().keySet().equals(binding.request().crew().keySet()))
                instances.start(prepared.matchId());
        } catch (Exception failure) {
            // An unknown allocation outcome must not be followed by another allocation under this lease.
            failNetwork("network_preparation_failed");
        }
    }

    private void failNetwork(String reason) {
        failed = true; instances.drain(); profiles.drain();
        for (var instance : instances.all()) {
            try { instances.stop(instance.match().id(), reason); }
            catch (RuntimeException failure) { plugin.getLogger().warning("Network match cleanup will retry."); }
        }
        evacuation = store.network().lobby(server).toCompletableFuture();
        evacuationDeadline = System.nanoTime() + 5_000_000_000L;
        plugin.getLogger().severe(reason + ": admission and heartbeats stopped. Restart after restoring service; abandoned reservations recover after lease expiry.");
    }
    private void evacuate(long now) {
        if (!evacuationSent && evacuation != null && evacuation.isDone()) {
            evacuationSent = true;
            try {
                var target = evacuation.join();
                if (target.isPresent() && !target.orElseThrow().equals(server))
                    for (var c : List.copyOf(connections.values())) if (current(c)) transfer(c.player, target.orElseThrow());
            } catch (RuntimeException unavailable) { /* bounded fallback to disconnect below */ }
        }
        if (now >= evacuationDeadline)
            for (var c : List.copyOf(connections.values())) c.player.kick(Component.text("Network service unavailable. Reconnect through the proxy lobby."));
    }

    private void route(Connection c, Optional<MongoNetwork.Route> route) {
        UUID id = c.player.getUniqueId();
        if (route.isEmpty()) {
            c.reservation = null; c.accepted = null;
            if (role.equals("backend")) { returnToLobby(c); return; }
            if (!c.profileOpened) { profiles.open(id); c.profileOpened = true; }
            profiles.unfreeze(id); crews.unlock(id);
            return;
        }
        var state = route.orElseThrow(); var reservation = state.reservation(); c.reservation = reservation;
        if (c.profileOpened && profiles.status(id) == ProfileSessions.Status.READY) profiles.freeze(id);
        if (!state.ready()) {
            later(c, store.network().recover(reservation.request().id()), recovered -> {
                if (recovered) say(c.player, "Queued match unavailable. Reservation released; retry from the lobby.");
            });
            return;
        }
        var target = reservation.assignment().orElseThrow();
        if (role.equals("lobby")) {
            if (!reservation.request().expiresAt().isAfter(Instant.now())) return;
            if (reservation.admissionAttempts().containsKey(id)) return; // A reconnect never reuses a consumed connection attempt.
            transfer(c.player, target.serverId()); return;
        }
        if (!target.equals(prepared) || binding == null || !binding.request().id().equals(reservation.request().id())) { returnToLobby(c); return; }
        if (instances.instanceOf(id).isPresent()) return;
        if (reservation.admissionAttempts().containsKey(id) && !c.attempt.equals(reservation.admissionAttempts().get(id))) {
            returnToLobby(c); return;
        }
        if (!packs.ready(c.player)) return;
        later(c, store.reservations().admit(reservation.request().id(), id, target, c.attempt), accepted -> {
            c.accepted = accepted;
            if (!c.profileOpened) { profiles.open(id); c.profileOpened = true; }
        });
    }
    private void admitLocally(Connection c) {
        UUID id = c.player.getUniqueId();
        var target = c.accepted.assignment().orElseThrow();
        if (!target.equals(prepared)) { c.accepted = null; returnToLobby(c); return; }
        try {
            profiles.freeze(id);
            instances.joinReserved(id, c.attempt, c.accepted);
            var world = Objects.requireNonNull(Bukkit.getWorld(instances.snapshot(target.matchId()).worldName()));
            players.enter(c.player, world.getSpawnLocation());
            c.accepted = null;
            c.admitted = true;
            say(c.player, "Reserved practice crew admitted. Waiting for the whole crew; no rewards.");
        } catch (RuntimeException failure) {
            if (instances.all().stream().anyMatch(i -> i.match().id().equals(target.matchId()))) instances.stop(target.matchId(), "reserved_transfer_failed");
            c.accepted = null; returnToLobby(c);
        }
    }
    private void returnToLobby(Connection c) {
        long now = System.nanoTime();
        if (c.returningSince == 0) c.returningSince = now;
        if (now - c.returningSince > 10_000_000_000L) {
            c.player.kick(Component.text("Lobby transfer was not completed. Please reconnect through the proxy.")); return;
        }
        later(c, store.network().lobby(server), lobby -> {
            if (lobby.isPresent() && !lobby.orElseThrow().equals(server)) transfer(c.player, lobby.orElseThrow());
            else c.player.kick(Component.text("No healthy lobby is available. Please reconnect shortly."));
        });
    }
    private void transfer(Player player, String target) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF(target); }
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (IOException failure) { throw new IllegalStateException("Could not encode proxy transfer", failure); }
    }
    private <T> void later(Connection c, CompletionStage<T> operation, Consumer<T> success) {
        if (c.pending != null) throw new IllegalStateException("Network operation already pending");
        var future = operation.toCompletableFuture(); c.pending = future;
        c.completion = () -> { if (current(c)) success.accept(future.join()); };
    }
    private boolean current(Connection c) { return c.player.isOnline() && Bukkit.getPlayer(c.player.getUniqueId()) == c.player && connections.get(c.player.getUniqueId()) == c; }
    private static void say(Player p, String text) { p.sendMessage(Component.text(text)); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p) || !p.hasPermission("heist.play")) return true;
        var c = connections.get(p.getUniqueId());
        try {
            if (!role.equals("lobby") || failed || lastLease == 0 || c == null || c.pending != null || c.queuePending) throw new IllegalStateException("Lobby network is not ready");
            String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            if (!Set.of("help", "status", "cancel").contains(action) && (!c.profileOpened || c.reservation != null))
                throw new IllegalStateException("Crew is queued or profile verification is pending");
            switch (action) {
                case "status" -> {
                    var members = c.reservation == null ? crews.members(p.getUniqueId()) : c.reservation.request().crew().keySet();
                    say(p, "Crew: " + members.stream().map(id -> {
                        var member = Bukkit.getPlayer(id); return member == null ? id.toString() : member.getName();
                    }).sorted().toList());
                    say(p, c.reservation == null ? "Not queued. Profile: " + profiles.status(p.getUniqueId())
                            : "Queued: " + c.reservation.request().difficulty() + "; deadline " + c.reservation.request().expiresAt()
                            + "; admitted " + c.reservation.admissionAttempts().size() + "/" + members.size()
                            + ". Reconnected players wait for cleanup if their admission was already consumed.");
                }
                case "invite" -> {
                    if (args.length != 2) throw new IllegalArgumentException("Use /heistnet invite <online-player>");
                    var target = Objects.requireNonNull(Bukkit.getPlayerExact(args[1]), "Player must be in this lobby");
                    crews.invite(p.getUniqueId(), target.getUniqueId()); say(target, p.getName() + " invited you. /heistnet accept (30 seconds)");
                }
                case "accept" -> { crews.accept(p.getUniqueId()); say(p, "Crew invitation accepted."); }
                case "leave" -> { crews.leave(p.getUniqueId()); say(p, "Left crew."); }
                case "cancel" -> {
                    if (c.reservation == null) throw new IllegalStateException("No confirmed queued reservation; wait for refresh");
                    later(c, store.reservations().cancelUnclaimed(c.reservation.request().id()), ignored -> {
                        crews.unlock(p.getUniqueId()); say(p, "Unclaimed reservation cancelled. Waiting for profile unlock.");
                    });
                }
                case "queue" -> queue(c, args);
                default -> say(p, "/heistnet invite <player> | accept | leave | status | queue [NORMAL|HARD] | cancel. Network practice only; no rewards.");
            }
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException failure) { say(p, "Cannot perform network action: " + failure.getMessage()); }
        return true;
    }
    private void queue(Connection c, String[] args) {
        if (queued.size() >= 128) throw new IllegalStateException("Queue submissions are at capacity");
        var difficultyChoice = args.length > 1 ? Difficulty.valueOf(args[1].toUpperCase(Locale.ROOT)) : Difficulty.NORMAL;
        Set<UUID> members = crews.lock(c.player.getUniqueId());
        List<Connection> crewConnections = new ArrayList<>();
        try {
            Map<UUID, Loadout> loadouts = new HashMap<>();
            for (UUID member : members) {
                var player = Bukkit.getPlayer(member);
                if (player == null || !packs.ready(player)) throw new IllegalStateException("All crew members need the pack in this lobby");
                var memberConnection = connections.get(member);
                if (memberConnection == null || !memberConnection.profileOpened || memberConnection.queuePending || memberConnection.reservation != null)
                    throw new IllegalStateException("Crew member already queued or still awaiting verification");
                crewConnections.add(memberConnection);
                loadouts.put(member, profiles.require(member).selectedLoadout());
            }
            members.forEach(profiles::freeze);
            Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
            var request = new CrewReservation(UUID.randomUUID(), arena, difficultyChoice, loadouts, now, now.plusSeconds(120));
            var future = store.reservations().reserve(request).toCompletableFuture();
            crewConnections.forEach(member -> { member.queuePending = true; member.routeEpoch++; });
            queued.add(new QueuedCrew(request, List.copyOf(crewConnections), future));
            say(c.player, "Submitting crew for " + difficultyChoice + ". Waiting for an exact backend (up to 120 seconds).");
        } catch (RuntimeException failure) {
            crews.unlock(c.player.getUniqueId());
            crewConnections.forEach(member -> profiles.unfreeze(member.player.getUniqueId()));
            throw failure;
        }
    }
    private void reconcileQueues() {
        for (var operation : List.copyOf(queued)) {
            if (!operation.future.isDone()) continue;
            try { operation.future.join(); }
            catch (RuntimeException uncertain) {
                // Keep the immutable roster frozen while an ambiguous write can still be admitted.
                if (operation.request.expiresAt().isAfter(Instant.now())) continue;
            }
            for (var c : operation.connections) if (current(c)) {
                c.queuePending = false; c.nextQuery = 0;
            }
            queued.remove(operation);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void commands(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase(Locale.ROOT).replaceFirst("^/[^ :]+:", "/");
        var c = connections.get(event.getPlayer().getUniqueId());
        if (!command.startsWith("/heist ") && !command.equals("/heist")) return;
        if (failed || c == null || !c.profileOpened) {
            event.setCancelled(true); say(event.getPlayer(), "Use /heistnet for network crews. Admission/profile verification may still be pending.");
        }
    }
    private boolean quarantined(Player player) {
        return failed || (role.equals("backend") && instances.instanceOf(player.getUniqueId()).isEmpty());
    }
    @EventHandler(ignoreCancelled = true) public void move(PlayerMoveEvent e) {
        if (!(e instanceof PlayerTeleportEvent) && quarantined(e.getPlayer()) && e.hasChangedPosition()) e.setCancelled(true);
    }
    @EventHandler public void interact(PlayerInteractEvent e) { if (quarantined(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent e) { if (quarantined(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void swap(PlayerSwapHandItemsEvent e) { if (quarantined(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void entityInteract(PlayerInteractEntityEvent e) { if (quarantined(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void breakBlock(org.bukkit.event.block.BlockBreakEvent e) { if (quarantined(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void placeBlock(org.bukkit.event.block.BlockPlaceEvent e) { if (quarantined(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void pickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && quarantined(p)) e.setCancelled(true);
    }
    @EventHandler public void inventory(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && quarantined(p)) e.setCancelled(true);
    }
    @EventHandler public void drag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && quarantined(p)) e.setCancelled(true);
    }
    @EventHandler public void damage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && quarantined(p)) e.setCancelled(true);
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player p && quarantined(p)) e.setCancelled(true);
    }
    private record QueuedCrew(CrewReservation request, List<Connection> connections, CompletableFuture<ReservationSnapshot> future) { }
    private static final class Connection {
        private final Player player;
        private final UUID attempt = UUID.randomUUID();
        private CompletableFuture<?> pending;
        private Runnable completion;
        private long nextQuery, routeEpoch, returningSince;
        private boolean profileOpened, queuePending, admitted;
        private ReservationSnapshot reservation, accepted;
        private Connection(Player player) { this.player = player; }
    }
}
