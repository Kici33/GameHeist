package dev.gameheist.paper.listener;

import dev.gameheist.runtime.persistence.ProfileSessions;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ProfileListener implements Listener {
    private final ProfileSessions profiles;
    public ProfileListener(ProfileSessions profiles) { this.profiles = profiles; }
    @EventHandler public void onJoin(PlayerJoinEvent event) { profiles.open(event.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { profiles.close(event.getPlayer().getUniqueId()); }
}
