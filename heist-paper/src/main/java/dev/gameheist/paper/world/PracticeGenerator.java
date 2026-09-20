package dev.gameheist.paper.world;

import org.bukkit.Material;
import org.bukkit.generator.*;
import java.util.Random;

/** Stateless base generator: Paper may call it concurrently. Versioned scenes are added on the owner thread. */
public final class PracticeGenerator extends ChunkGenerator {
    @Override public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        data.setRegion(0, 64, 0, 16, 65, 16, Material.STONE);
    }
}
