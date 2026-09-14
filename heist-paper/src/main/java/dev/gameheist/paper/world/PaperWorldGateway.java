package dev.gameheist.paper.world;

import dev.gameheist.domain.arena.ArenaDefinition;
import dev.gameheist.runtime.instance.*;
import org.bukkit.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public final class PaperWorldGateway implements WorldGateway {
    private final PlayerSessions players;
    private final Path container;
    private final Logger logger;
    private boolean creationBlocked;
    private final List<CompletableFuture<Void>> partialCleanups = new ArrayList<>();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("heist-world-cleanup").factory());

    public PaperWorldGateway(PlayerSessions players, Logger logger) throws IOException {
        this.players = players;
        this.logger = logger;
        container = Bukkit.getWorldContainer().toPath().toRealPath();
    }

    @Override public WorldInstance create(UUID id, ArenaDefinition arena) throws IOException {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World creation requires server thread");
        if (creationBlocked) throw new IOException("A partial world could not unload; restart after inspection before creating more instances");
        // The generator is intentionally specific to graybox content.
        if (arena.spawn().y() != 65 || arena.bounds().minimum().y() < 64) {
            throw new IllegalArgumentException("Practice arenas require spawn Y=65 and minimum Y>=64");
        }
        if (arena.heist().isPresent() && arena.heist().orElseThrow().allPositions().stream().anyMatch(p -> p.y() != 65)) {
            throw new IllegalArgumentException("Generated graybox interaction blocks must be at Y=65");
        }
        if (arena.guards().stream().flatMap(guard -> guard.patrol().stream()).anyMatch(point -> point.y() != 65)) {
            throw new IllegalArgumentException("Generated graybox guard routes must be at Y=65");
        }
        String name = "heist_" + id.toString().replace("-", "");
        Path directory = container.resolve(name);
        if (Files.exists(directory) || Bukkit.getWorld(name) != null) throw new IOException("World name already exists");
        World world = null;
        try {
            world = new WorldCreator(name).generator(new PracticeGenerator()).generateStructures(false).createWorld();
            if (world == null) throw new IOException("Paper did not create the instance world");
            world.setAutoSave(false);
            world.setDifficulty(org.bukkit.Difficulty.NORMAL);
            world.setGameRule(GameRules.SPAWN_MOBS, false);
            world.setGameRule(GameRules.RAIDS, false);
            world.setGameRule(GameRules.MOB_GRIEFING, false);
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setTime(6000);
            var spawn = arena.spawn();
            world.setSpawnLocation(new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()));
            world.getWorldBorder().setCenter((arena.bounds().minimum().x() + arena.bounds().maximum().x()) / 2,
                    (arena.bounds().minimum().z() + arena.bounds().maximum().z()) / 2);
            world.getWorldBorder().setSize(Math.max(arena.bounds().maximum().x() - arena.bounds().minimum().x(),
                    arena.bounds().maximum().z() - arena.bounds().minimum().z()) + 1);
            world.getChunkAt(world.getSpawnLocation()).load();
            if (arena.heist().isPresent()) GrayboxScene.build(world, arena.heist().orElseThrow());
            for (var block : arena.cover()) world.getBlockAt(block.x(), block.y(), block.z()).setType(Material.STONE_BRICKS, false);
            return new Handle(name, directory, world);
        } catch (Exception failure) {
            if (world == null) world = Bukkit.getWorld(name);
            if (world != null && !Bukkit.unloadWorld(world, false)) {
                creationBlocked = true;
                logger.severe("Failed creation left a loaded world requiring manual cleanup: " + name);
            } else {
                partialCleanups.add(CompletableFuture.runAsync(() -> {
                    try { deleteOwnedWorld(directory); }
                    catch (IOException error) {
                        logger.warning("Failed creation cleanup: " + error);
                        throw new CompletionException(error);
                    }
                }, cleanup));
            }
            throw new IOException("Could not create practice world " + name, failure);
        }
    }

    public boolean accepting() { return !creationBlocked; }
    public boolean cleanupHealthy() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Cleanup status requires server thread");
        partialCleanups.removeIf(future -> future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled());
        return !creationBlocked && partialCleanups.isEmpty();
    }

    public void shutdown() {
        cleanup.shutdown();
        try {
            if (!cleanup.awaitTermination(2, TimeUnit.SECONDS)) logger.warning("World cleanup still finishing in background");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void deleteOwnedWorld(Path directory) throws IOException {
        // Only an exact generated child of the verified world container may be removed.
        if (!directory.normalize().getParent().equals(container)
                || !directory.getFileName().toString().matches("heist_[0-9a-f]{32}")
                || Files.isSymbolicLink(directory)) throw new IOException("Unsafe world cleanup path");
        if (!Files.exists(directory)) return;
        if (!directory.toRealPath().getParent().equals(container)) throw new IOException("World escaped container");
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private final class Handle implements WorldInstance {
        private final String name;
        private final Path directory;
        private World world;
        private CompletableFuture<Void> deletion;
        private Handle(String name, Path directory, World world) {
            this.name = name;
            this.directory = directory;
            this.world = world;
        }
        @Override public String name() { return name; }
        @Override public void release() throws IOException {
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World unload requires server thread");
            if (world != null) {
                for (var player : List.copyOf(world.getPlayers())) players.restore(player);
                if (!Bukkit.unloadWorld(world, false)) throw new IOException("World unload refused: " + name);
                world = null;
            }
            if (deletion == null) {
                deletion = CompletableFuture.runAsync(() -> {
                    try { deleteOwnedWorld(directory); }
                    catch (IOException failure) { throw new CompletionException(failure); }
                }, cleanup);
            }
            if (!deletion.isDone()) throw new IOException("World file cleanup pending: " + name);
            try { deletion.join(); }
            catch (CompletionException failure) {
                deletion = null;
                throw new IOException("World file cleanup failed: " + name, failure.getCause());
            }
        }
    }
}
