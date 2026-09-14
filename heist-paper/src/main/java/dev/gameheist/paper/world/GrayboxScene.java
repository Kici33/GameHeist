package dev.gameheist.paper.world;

import dev.gameheist.domain.arena.BlockPosition;
import dev.gameheist.domain.objective.HeistDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

/** Readable placeholder props; their material is presentation, not objective authority. */
public final class GrayboxScene {
    private GrayboxScene() {}
    public static void build(World world, HeistDefinition definition) {
        marker(world, definition.security(), Material.LODESTONE, Material.BLUE_CONCRETE, "1 · SECURITY — Right click");
        marker(world, definition.drill(), Material.BLAST_FURNACE, Material.ORANGE_CONCRETE, "2 · DRILL — Install / repair");
        for (int i = 0; i < definition.bags().size(); i++) {
            marker(world, definition.bags().get(i), Material.GOLD_BLOCK, Material.YELLOW_CONCRETE,
                    "3 · LOOT BAG " + (i + 1));
        }
        marker(world, definition.extraction(), Material.EMERALD_BLOCK, Material.GREEN_CONCRETE,
                "4 · EXTRACTION — Deposit / vote");
    }
    private static void marker(World world, BlockPosition position, Material prop, Material floor, String label) {
        world.getBlockAt(position.x(), position.y(), position.z()).setType(prop, false);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            world.getBlockAt(position.x() + x, position.y() - 1, position.z() + z).setType(floor, false);
        }
        world.spawn(new Location(world, position.x() + 0.5, position.y() + 2, position.z() + 0.5),
                TextDisplay.class, display -> {
                    display.text(Component.text(label, NamedTextColor.WHITE));
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setSeeThrough(false);
                    display.setShadowed(true);
                    display.setPersistent(false);
                });
    }
}
