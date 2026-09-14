package dev.gameheist.paper.pack;

import net.kyori.adventure.resource.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.net.URI;
import java.util.*;

public final class ResourcePackGate implements Listener {
    private final JavaPlugin plugin;
    private final boolean bypass;
    private final UUID packId;
    private final ResourcePackRequest request;
    private final long timeoutTicks;
    private final Set<UUID> loaded = new HashSet<>();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public ResourcePackGate(JavaPlugin plugin) {
        this.plugin = plugin;
        var config = plugin.getConfig();
        bypass = config.getBoolean("resource-pack.development-bypass");
        packId = UUID.fromString(Objects.requireNonNull(config.getString("resource-pack.id")));
        long seconds = config.getLong("resource-pack.timeout-seconds");
        if (seconds < 5 || seconds > 300) throw new IllegalArgumentException("Pack timeout must be 5–300 seconds");
        timeoutTicks = seconds * 20;
        if (bypass) {
            request = null;
            plugin.getLogger().warning("DEVELOPMENT: resource-pack admission bypass enabled; practice use only.");
        } else {
            URI uri = URI.create(Objects.requireNonNull(config.getString("resource-pack.url")));
            String hash = Objects.requireNonNull(config.getString("resource-pack.sha1"));
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !hash.matches("[0-9a-fA-F]{40}")) {
                throw new IllegalArgumentException("Pack requires an HTTPS URL and a 40-character SHA-1");
            }
            request = ResourcePackRequest.resourcePackRequest()
                    .packs(ResourcePackInfo.resourcePackInfo(packId, uri, hash))
                    .required(true).prompt(Component.text("GameHeist requires its resource pack.")).build();
        }
    }

    public boolean ready(Player player) { return bypass || loaded.contains(player.getUniqueId()); }
    public void request(Player player) {
        if (bypass) return;
        UUID id = player.getUniqueId();
        loaded.remove(id);
        cancel(id);
        player.sendResourcePacks(Objects.requireNonNull(request));
        pending.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            if (player.isOnline() && !loaded.contains(id)) player.kick(Component.text("Resource pack timed out. Please reconnect to retry."));
        }, timeoutTicks));
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { request(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        loaded.remove(event.getPlayer().getUniqueId());
        cancel(event.getPlayer().getUniqueId());
    }
    @EventHandler public void onStatus(PlayerResourcePackStatusEvent event) {
        if (bypass || !packId.equals(event.getID())) return;
        UUID id = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> { loaded.add(id); cancel(id); }
            case ACCEPTED, DOWNLOADED -> { }
            default -> {
                loaded.remove(id);
                cancel(id);
                event.getPlayer().kick(Component.text("The required pack could not be applied. Please reconnect to retry."));
            }
        }
    }
    public void close() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
        loaded.clear();
    }
    private void cancel(UUID id) {
        var task = pending.remove(id);
        if (task != null) task.cancel();
    }
}
