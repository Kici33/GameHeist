package dev.gameheist.paper.world;

import org.bukkit.Material;
import org.bukkit.generator.*;
import java.util.Random;

/** Stateless generator: Paper may call it concurrently. The practice floor is at Y=64. */
public final class PracticeGenerator extends ChunkGenerator {
    @Override public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        data.setRegion(0, 64, 0, 16, 65, 16, Material.STONE);
    }
}
