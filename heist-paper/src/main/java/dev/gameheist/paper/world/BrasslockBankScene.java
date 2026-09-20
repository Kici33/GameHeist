package dev.gameheist.paper.world;

import dev.gameheist.domain.arena.BlockPosition;
import dev.gameheist.domain.objective.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;

/** Deterministic, versioned Brasslock Bank structure. Gameplay anchors remain manifest-owned. */
public final class BrasslockBankScene {
    private BrasslockBankScene() {}

    public static void build(World world, HeistDefinition heist) {
        BankRouteDefinition route = heist.bankRoute().orElseThrow();
        fill(world, -48, 64, -40, 48, 64, 40, Material.DEEPSLATE_TILES);

        // Briefing/tutorial apron and extraction yard.
        fill(world, -47, 64, -9, -35, 64, 9, Material.POLISHED_ANDESITE);
        rail(world, -47, -35, -9); rail(world, -47, -35, 9);
        label(world, -41.5, 68, 0.5, "BRIEFING · STAFF ←  BANK  → ROOF", NamedTextColor.GOLD);
        label(world, -43.5, 66.2, -5.5, "1  LEFT CLICK · FIRE", NamedTextColor.WHITE);
        label(world, -43.5, 66.2, 0.5, "2  RIGHT CLICK · OBJECTIVES", NamedTextColor.WHITE);
        label(world, -43.5, 66.2, 6.5, "3  SNEAK + CLICK · REVIVE", NamedTextColor.WHITE);
        fill(world, -47, 64, 18, -27, 64, 36, Material.SMOOTH_STONE);
        stripes(world, -46, -28, 20, 34);
        label(world, -37.5, 68, 27.5, "EXTRACTION YARD", NamedTextColor.GREEN);

        // Bank shell, public hall and readable front entrances.
        floor(world, -32, 32, -16, 16, Material.POLISHED_DIORITE);
        walls(world, -32, 32, -16, 16, 65, 72, Material.STONE_BRICKS);
        fill(world, -31, 72, -15, 31, 72, 15, Material.SMOOTH_STONE);
        doorway(world, -32, 65, -3, -32, 68, 3); // public entrance
        doorway(world, -8, 65, -16, -3, 68, -16); // staff entrance from alley
        fill(world, -31, 64, -14, -10, 64, 14, Material.SMOOTH_QUARTZ);
        fill(world, -27, 65, -10, -25, 67, 10, Material.DARK_OAK_PLANKS); // teller line/cover
        for (int z : new int[]{-10, -5, 0, 5, 10}) fill(world, -20, 65, z, -17, 66, z + 1, Material.DARK_OAK_PLANKS);
        label(world, -20.5, 70, 0.5, "PUBLIC HALL", NamedTextColor.WHITE);

        // Staff corridor with a loop into security and the vault approach.
        fill(world, -9, 64, -15, 7, 64, -7, Material.POLISHED_ANDESITE);
        wallX(world, -9, 8, -6, 65, 69, Material.BRICKS);
        doorway(world, -4, 65, -6, 0, 68, -6);
        label(world, -1, 68.5, -10.5, "STAFF CORRIDOR", NamedTextColor.AQUA);
        room(world, -8, 6, 5, 15, Material.DEEPSLATE_BRICKS, Material.GRAY_CONCRETE);
        doorway(world, -8, 65, 8, -8, 68, 12);
        doorway(world, 0, 65, 5, 4, 68, 5);
        label(world, -1, 69, 10, "SECURITY", NamedTextColor.BLUE);

        // Vault approach has two lanes around central cover.
        fill(world, 7, 64, -14, 20, 64, 14, Material.TUFF_BRICKS);
        fill(world, 10, 65, -3, 13, 68, 3, Material.REINFORCED_DEEPSLATE);
        fill(world, 16, 65, -14, 18, 67, -11, Material.IRON_BLOCK);
        fill(world, 16, 65, 11, 18, 67, 14, Material.IRON_BLOCK);
        label(world, 13.5, 70, 0.5, "VAULT APPROACH", NamedTextColor.GOLD);
        room(world, 21, 36, -13, 13, Material.DEEPSLATE_BRICKS, Material.IRON_BLOCK);
        doorway(world, 21, 65, -3, 21, 69, 3);
        label(world, 29, 70, 0, "BRASSLOCK VAULT", NamedTextColor.YELLOW);

        // Side alley loops around the north wall to the escape switch and yard.
        fill(world, -33, 64, -24, 33, 64, -17, Material.COBBLED_DEEPSLATE);
        fill(world, -33, 65, -25, 33, 67, -25, Material.BRICK_WALL);
        label(world, -2, 68, -21, "SIDE ALLEY · ESCAPE SWITCH", NamedTextColor.GOLD);
        for (int x = -28; x <= 28; x += 8) fill(world, x, 65, -24, x + 1, 67, -23, Material.OAK_PLANKS);

        // Roof route: two stair towers, a parapet loop and skylight approach.
        for (int step = 0; step < 8; step++) {
            fill(world, -34 + step, 65 + step, 12, -32 + step, 65 + step, 15, Material.STONE_BRICK_STAIRS);
            fill(world, -34 + step, 66 + step, 12, -32 + step, 67 + step, 15, Material.AIR);
            fill(world, 5 + step, 72 - step, 12, 7 + step, 72 - step, 15, Material.STONE_BRICK_STAIRS);
            fill(world, 5 + step, 73 - step, 12, 7 + step, 74 - step, 15, Material.AIR);
        }
        fill(world, -27, 72, 12, 8, 72, 15, Material.SMOOTH_STONE);
        fill(world, -27, 73, 11, 8, 73, 11, Material.STONE_BRICK_WALL);
        fill(world, -27, 73, 16, 8, 73, 16, Material.STONE_BRICK_WALL);
        label(world, -10, 76, 13.5, "ROOF ROUTE", NamedTextColor.AQUA);

        // Authored gameplay landmarks.
        marker(world, route.keycard(), Material.CHISELED_BOOKSHELF, "KEYCARD · COLLECT", NamedTextColor.YELLOW);
        marker(world, heist.security(), Material.LODESTONE, "SECURITY TERMINAL · DISABLE CAMERAS", NamedTextColor.BLUE);
        marker(world, heist.drill(), Material.BLAST_FURNACE, "VAULT DRILL · INSTALL / REPAIR", NamedTextColor.GOLD);
        marker(world, route.power(), Material.REDSTONE_LAMP, "POWER SUPPORT · HOLD POSITION", NamedTextColor.RED);
        marker(world, route.escapeActivation(), Material.POLISHED_BLACKSTONE, "ESCAPE SWITCH · CREW VOTE", NamedTextColor.GREEN);
        marker(world, heist.extraction(), Material.EMERALD_BLOCK, "LOOT DROP · EXTRACTION YARD", NamedTextColor.GREEN);
        for (int index = 0; index < heist.bags().size(); index++) marker(world, heist.bags().get(index),
                Material.GOLD_BLOCK, "LOOT " + (index + 1), NamedTextColor.YELLOW);
        for (BlockPosition camera : route.cameras()) {
            world.getBlockAt(camera.x(), camera.y(), camera.z()).setType(Material.OBSERVER, false);
            label(world, camera.x() + .5, camera.y() + 1.4, camera.z() + .5, "CAMERA", NamedTextColor.RED);
        }
        // Extraction boundary is readable without relying on green alone.
        int radius = (int) Math.floor(route.extractionRadius());
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            double distance = Math.sqrt(x * x + z * z);
            if (distance >= radius - .75 && distance <= radius + .25) world.getBlockAt(
                    route.extractionArea().x() + x, 64, route.extractionArea().z() + z).setType(Material.SEA_LANTERN, false);
        }
    }

    private static void room(World world, int minX, int maxX, int minZ, int maxZ, Material wall, Material floor) {
        floor(world, minX, maxX, minZ, maxZ, floor); walls(world, minX, maxX, minZ, maxZ, 65, 70, wall);
    }
    private static void floor(World w, int minX, int maxX, int minZ, int maxZ, Material material) { fill(w, minX, 64, minZ, maxX, 64, maxZ, material); }
    private static void walls(World w, int minX, int maxX, int minZ, int maxZ, int minY, int maxY, Material material) {
        wallX(w, minX, maxX, minZ, minY, maxY, material); wallX(w, minX, maxX, maxZ, minY, maxY, material);
        fill(w, minX, minY, minZ, minX, maxY, maxZ, material); fill(w, maxX, minY, minZ, maxX, maxY, maxZ, material);
    }
    private static void wallX(World w, int minX, int maxX, int z, int minY, int maxY, Material material) { fill(w, minX, minY, z, maxX, maxY, z, material); }
    private static void doorway(World w, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { fill(w, minX, minY, minZ, maxX, maxY, maxZ, Material.AIR); }
    private static void fill(World w, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Material material) {
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++)
            w.getBlockAt(x, y, z).setType(material, false);
    }
    private static void rail(World w, int minX, int maxX, int z) { fill(w, minX, 65, z, maxX, 65, z, Material.IRON_BARS); }
    private static void stripes(World w, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x += 4) fill(w, x, 64, minZ, x, 64, maxZ, Material.YELLOW_CONCRETE);
    }
    private static void marker(World world, BlockPosition position, Material material, String text, NamedTextColor color) {
        world.getBlockAt(position.x(), position.y(), position.z()).setType(material, false);
        label(world, position.x() + .5, position.y() + 2.4, position.z() + .5, text, color);
    }
    private static void label(World world, double x, double y, double z, String text, NamedTextColor color) {
        world.spawn(new Location(world, x, y, z), TextDisplay.class, display -> {
            display.text(Component.text(text, color)); display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true); display.setPersistent(false);
        });
    }
}
