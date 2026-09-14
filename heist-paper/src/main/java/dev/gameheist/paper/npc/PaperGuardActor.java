package dev.gameheist.paper.npc;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.npc.GuardDefinition;
import dev.gameheist.runtime.npc.GuardActor;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import java.io.IOException;
import java.util.*;

/** A native mob with vanilla goals removed; only the squad supplies movement decisions. */
final class PaperGuardActor implements GuardActor {
    private final Vindicator entity;
    private final Set<UUID> ownedEntities;
    private boolean released;

    PaperGuardActor(World world, GuardDefinition definition, Set<UUID> ownedEntities, boolean combat) throws IOException {
        var start = definition.patrol().getFirst();
        Vindicator spawned = null;
        var partiallyCreated = new java.util.concurrent.atomic.AtomicReference<Vindicator>();
        try {
            spawned = world.spawn(location(world, start), Vindicator.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, guard -> {
                partiallyCreated.set(guard);
                guard.setPersistent(false);
                guard.setRemoveWhenFarAway(false);
                guard.setCanJoinRaid(false);
                guard.setPatrolLeader(false);
                guard.setCanPickupItems(false);
                guard.setCollidable(false);
                guard.setSilent(true);
                // Combat actors must emit click damage events; the controller cancels all vanilla damage.
                guard.setInvulnerable(!combat);
                guard.setLootTable(null);
                guard.getEquipment().clear();
                Bukkit.getMobGoals().removeAllGoals(guard);
                guard.setTarget(null);
                guard.getPathfinder().setCanOpenDoors(false);
                guard.customName(Component.text(definition.id() + " · PATROL"));
                guard.setCustomNameVisible(true);
                ownedEntities.add(guard.getUniqueId());
            });
            if (!spawned.isValid()) throw new IOException("Guard spawn was cancelled: " + definition.id());
            entity = spawned;
        } catch (Exception failure) {
            if (spawned == null) spawned = partiallyCreated.get();
            if (spawned != null) {
                ownedEntities.remove(spawned.getUniqueId());
                spawned.remove();
            }
            throw new IOException("Could not spawn guard " + definition.id(), failure);
        }
        this.ownedEntities = ownedEntities;
    }

    @Override public boolean alive() { return !released && entity.isValid() && !entity.isDead(); }
    org.bukkit.util.BoundingBox hitBox() { return entity.getBoundingBox(); }
    @Override public Position position() { return position(entity.getLocation()); }
    @Override public Position eyePosition() { return position(entity.getEyeLocation()); }
    @Override public boolean canSee(UUID playerId) {
        var player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() && player.getWorld().equals(entity.getWorld()) && entity.hasLineOfSight(player);
    }
    @Override public boolean moveTo(Position position, double speed) {
        return entity.getPathfinder().moveTo(location(entity.getWorld(), position), speed);
    }
    @Override public void stop() { if (alive()) entity.getPathfinder().stopPathfinding(); }
    @Override public void lookAt(Position position) {
        entity.lookAt(new Location(entity.getWorld(), position.x(), position.y() + 1.5, position.z()));
    }
    @Override public void label(String text) { entity.customName(Component.text(text)); }
    @Override public void release() {
        if (released) return;
        stop();
        entity.remove();
        ownedEntities.remove(entity.getUniqueId());
        released = true;
    }
    static Position position(Location value) { return new Position(value.getX(), value.getY(), value.getZ(), value.getYaw(), value.getPitch()); }
    private static Location location(World world, Position value) {
        return new Location(world, value.x(), value.y(), value.z(), value.yaw(), value.pitch());
    }
}
