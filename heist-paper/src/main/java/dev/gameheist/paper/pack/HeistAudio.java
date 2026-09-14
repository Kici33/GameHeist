package dev.gameheist.paper.pack;

import dev.gameheist.paper.world.PlayerSessions;
import dev.gameheist.runtime.instance.InstanceSnapshot;
import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.function.Predicate;

/** Server-thread, crew-scoped audio. Short samples require no scheduled tasks or client loops. */
public final class HeistAudio {
    public enum Cue {
        CARBINE_FIRE("carbine_fire", "entity.firework_rocket.blast"),
        CARBINE_RELOAD("carbine_reload", "item.crossbow.loading_middle"),
        ALARM("alarm", "block.bell.use"),
        DRILL_WORK("drill_work", "block.piston.extend"),
        DRILL_JAM("drill_jam", "block.anvil.land"),
        DRILL_COMPLETE("drill_complete", "block.iron_door.open");
        private final String id;
        private final String fallback;
        Cue(String id, String fallback) { this.id = id; this.fallback = fallback; }
        public String id() { return id; }
        public String sound(boolean custom) { return custom ? "gameheist:" + id : "minecraft:" + fallback; }
    }
    private final PlayerSessions players;
    private final Predicate<UUID> enabled;
    public HeistAudio(PlayerSessions players, Predicate<UUID> enabled) { this.players = players; this.enabled = enabled; }
    public void emit(InstanceSnapshot instance, Location origin, Cue cue) {
        for (UUID id : instance.match().participants().keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || !players.contains(id) || !enabled.test(id)
                    || !player.getWorld().getName().equals(instance.worldName())) continue;
            Location source = origin == null ? player.getLocation() : origin;
            if (!source.getWorld().equals(player.getWorld()) || source.distanceSquared(player.getLocation()) > 24 * 24) continue;
            player.playSound(source, cue.sound(players.customModels()), SoundCategory.PLAYERS, .7f, 1f);
        }
    }
}
