package dev.gameheist.paper.world;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import dev.gameheist.paper.pack.PackModel;
import java.util.*;

/** Owns practice movement and, in combat arenas, a temporary inventory. Native health is untouched. */
public final class PlayerSessions {
    private final World fallback;
    private final boolean customModels;
    private final Map<UUID, SavedState> saved = new HashMap<>();
    private final Set<UUID> transferring = new HashSet<>();
    private final Map<UUID, InventoryState> inventories = new HashMap<>();
    private final Map<UUID, SuspendedState> suspended = new HashMap<>();
    private final Map<UUID, ArmorStand> avatars = new HashMap<>();
    private final Set<UUID> equipped = new HashSet<>();
    public PlayerSessions(World fallback, boolean customModels) {
        this.fallback = Objects.requireNonNull(fallback);
        this.customModels = customModels;
    }
    public boolean customModels() { return customModels; }

    public void enter(Player player, Location destination) {
        if (saved.containsKey(player.getUniqueId())) throw new IllegalStateException("Player already has a saved session");
        player.closeInventory();
        SavedState state = new SavedState(player.getLocation().clone(), player.getGameMode(), player.getWalkSpeed());
        saved.put(player.getUniqueId(), state);
        if (!transfer(player, destination)) {
            saved.remove(player.getUniqueId());
            throw new IllegalStateException("Teleport was cancelled");
        }
        player.setGameMode(GameMode.ADVENTURE);
    }

    public void restore(Player player) {
        SavedState state = saved.get(player.getUniqueId());
        Location destination = state == null ? fallback.getSpawnLocation() : state.location();
        if (destination.getWorld() == null || Bukkit.getWorld(destination.getWorld().getUID()) == null) {
            destination = fallback.getSpawnLocation();
        }
        if (!transfer(player, destination)) throw new IllegalStateException("Return teleport was cancelled");
        if (state != null) {
            player.setGameMode(state.gameMode());
            player.setWalkSpeed(state.walkSpeed());
        }
        InventoryState inventory = inventories.get(player.getUniqueId());
        if (inventory != null) {
            player.getInventory().setContents(copy(inventory.contents()));
            player.getInventory().setHeldItemSlot(inventory.heldSlot());
            inventories.remove(player.getUniqueId());
        }
        saved.remove(player.getUniqueId());
        suspended.remove(player.getUniqueId());
        equipped.remove(player.getUniqueId());
        removeAvatar(player.getUniqueId());
    }
    public boolean contains(UUID playerId) { return saved.containsKey(playerId); }
    public void equipCombat(Player player) {
        equipCombat(player, true);
    }
    public void equipCombat(Player player, boolean medkitAvailable) {
        UUID id = player.getUniqueId();
        if (!saved.containsKey(id) || suspended.containsKey(id) || equipped.contains(id)) return;
        player.closeInventory();
        inventories.putIfAbsent(id, new InventoryState(copy(player.getInventory().getContents()), player.getInventory().getHeldItemSlot()));
        player.getInventory().clear();
        ItemStack gun = new ItemStack(Material.IRON_HOE);
        gun.editMeta(meta -> {
            meta.displayName(Component.text("Carbine | Left click: fire | F: reload"));
            meta.setUnbreakable(true);
            if (customModels) meta.setItemModel(PackModel.CARBINE.key());
        });
        player.getInventory().setItem(0, gun);
        ItemStack medkit = new ItemStack(Material.PAPER);
        medkit.editMeta(meta -> {
            meta.displayName(Component.text("Medkit | Right click: heal 40 HP | One use"));
            if (customModels) meta.setItemModel(PackModel.MEDKIT.key());
        });
        if (medkitAvailable) player.getInventory().setItem(1, medkit);
        player.getInventory().setHeldItemSlot(0);
        equipped.add(id);
        player.sendMessage(Component.text("Carbine: left click to fire, F to reload. Sneak + right click a downed teammate, then keep sneaking nearby to revive."));
        player.sendMessage(Component.text("Slot 2: medkit. Right click to heal yourself, or click a nearby teammate to heal them. One use, up to 40 HP."));
    }
    /** Restores durable Bukkit state while retaining the authoritative match session for reconnect. */
    public void suspend(Player player, long generation) {
        UUID id = player.getUniqueId();
        if (!saved.containsKey(id)) return;
        SuspendedState current = suspended.get(id);
        if (current != null && current.generation() == generation) return;
        Location resume = player.getLocation().clone();
        removeAvatar(id);
        ArmorStand avatar = resume.getWorld().spawn(resume, ArmorStand.class, stand -> {
            stand.setPersistent(false);
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.customName(Component.text(player.getName() + " · DISCONNECTED"));
            stand.setCustomNameVisible(true);
        });
        avatars.put(id, avatar);
        suspended.put(id, new SuspendedState(resume, generation));
        equipped.remove(id);
        restoreOwnedState(player, false);
    }
    public boolean suspended(UUID playerId) { return suspended.containsKey(playerId); }
    public long suspendedGeneration(UUID playerId) {
        SuspendedState state = suspended.get(playerId);
        if (state == null) throw new IllegalStateException("Player is not suspended");
        return state.generation();
    }
    public Location avatarLocation(UUID playerId) {
        ArmorStand avatar = avatars.get(playerId);
        return avatar != null && avatar.isValid() ? avatar.getLocation() : null;
    }
    public org.bukkit.entity.LivingEntity combatEntity(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline() && !suspended.containsKey(playerId)) return online;
        ArmorStand avatar = avatars.get(playerId);
        return avatar != null && avatar.isValid() ? avatar : null;
    }
    public void hideAvatar(UUID playerId) { removeAvatar(playerId); }
    public void resume(Player player) {
        UUID id = player.getUniqueId();
        SuspendedState state = suspended.get(id);
        if (state == null) throw new IllegalStateException("Player has no suspended match session");
        if (!transfer(player, state.location())) throw new IllegalStateException("Reconnect teleport was cancelled");
        player.setGameMode(GameMode.ADVENTURE);
        suspended.remove(id);
        removeAvatar(id);
    }
    public void carrying(Player player, boolean carrying) {
        SavedState state = saved.get(player.getUniqueId());
        if (state == null) return;
        float speed = carrying ? state.walkSpeed() * 0.8f : state.walkSpeed();
        if (player.getWalkSpeed() != speed) player.setWalkSpeed(speed);
        if (customModels && inventories.containsKey(player.getUniqueId())) {
            // Slot 9 is owned by the temporary combat inventory; loot remains authoritative in HeistRun.
            var visual = player.getInventory().getItem(8);
            if (carrying && (visual == null || !visual.hasItemMeta() || !PackModel.LOOT_BAG.key().equals(visual.getItemMeta().getItemModel()))) {
                player.getInventory().setItem(8, PackModel.LOOT_BAG.item("Loot bag | Deliver to extraction"));
            } else if (!carrying && visual != null) player.getInventory().setItem(8, null);
        }
    }
    public boolean transferring(UUID playerId) { return transferring.contains(playerId); }
    private boolean transfer(Player player, Location destination) {
        transferring.add(player.getUniqueId());
        try { return player.teleport(destination); }
        finally { transferring.remove(player.getUniqueId()); }
    }
    private void restoreOwnedState(Player player, boolean removeInventory) {
        SavedState state = saved.get(player.getUniqueId());
        Location destination = state == null ? fallback.getSpawnLocation() : state.location();
        if (destination.getWorld() == null || Bukkit.getWorld(destination.getWorld().getUID()) == null) destination = fallback.getSpawnLocation();
        if (!transfer(player, destination)) throw new IllegalStateException("Return teleport was cancelled");
        if (state != null) { player.setGameMode(state.gameMode()); player.setWalkSpeed(state.walkSpeed()); }
        InventoryState inventory = inventories.get(player.getUniqueId());
        if (inventory != null) {
            player.getInventory().setContents(copy(inventory.contents()));
            player.getInventory().setHeldItemSlot(inventory.heldSlot());
            if (removeInventory) inventories.remove(player.getUniqueId());
        }
    }
    private void removeAvatar(UUID playerId) {
        ArmorStand avatar = avatars.remove(playerId);
        if (avatar != null) avatar.remove();
    }
    private record SavedState(Location location, GameMode gameMode, float walkSpeed) {}
    private record InventoryState(ItemStack[] contents, int heldSlot) {}
    private record SuspendedState(Location location, long generation) {}
    private static ItemStack[] copy(ItemStack[] contents) {
        return Arrays.stream(contents).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }
}
