package dev.gameheist.paper.pack;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** Stable contract with resource-pack/assets/gameheist/items. No global vanilla overrides. */
public enum PackModel {
    CARBINE("carbine", Material.IRON_HOE),
    LOOT_BAG("loot_bag", Material.PAPER),
    DRILL_IDLE("drill_idle", Material.PAPER),
    DRILL_RUNNING("drill_running", Material.PAPER),
    DRILL_JAMMED("drill_jammed", Material.PAPER),
    DRILL_COMPLETE("drill_complete", Material.PAPER),
    SECURITY_TERMINAL("security_terminal", Material.PAPER),
    SECURITY_DISABLED("security_disabled", Material.PAPER),
    EXTRACTION_BEACON("extraction_beacon", Material.PAPER);

    private final String id;
    private final Material material;
    PackModel(String id, Material material) { this.id = id; this.material = material; }
    public String id() { return id; }
    public NamespacedKey key() { return new NamespacedKey("gameheist", id); }
    public ItemStack item(String name) {
        var item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.setItemModel(key());
            meta.displayName(Component.text(name));
            meta.setUnbreakable(true);
        });
        return item;
    }
}
