package dev.gameheist.paper;

import dev.gameheist.domain.player.LoadoutCatalog;
import dev.gameheist.paper.command.HeistCommand;
import dev.gameheist.paper.metrics.PaperMetrics;
import dev.gameheist.runtime.metrics.InfluxMetricsExporter;
import java.net.URI;
import dev.gameheist.paper.command.StatisticsView;
import dev.gameheist.paper.config.ArenaLoader;
import dev.gameheist.paper.gameplay.HeistGameplay;
import dev.gameheist.paper.npc.GuardController;
import dev.gameheist.paper.npc.CombatController;
import dev.gameheist.paper.listener.PracticeListener;
import dev.gameheist.paper.pack.ResourcePackGate;
import dev.gameheist.paper.world.*;
import dev.gameheist.runtime.instance.InstanceManager;
import dev.gameheist.runtime.health.HealthServer;
import dev.gameheist.runtime.health.DrainController;
import dev.gameheist.runtime.persistence.InMemoryResultRepository;
import dev.gameheist.runtime.persistence.*;
import dev.gameheist.mongo.MongoStore;
import dev.gameheist.paper.listener.ProfileListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public final class HeistPlugin extends JavaPlugin {
    private InstanceManager instances;
    private PaperWorldGateway worlds;
    private ResourcePackGate packs;
    private HealthServer health;
    private MongoStore storage;
    private DrainController drain;
    private InfluxMetricsExporter metrics;
    private PaperMetrics metricsSampler;

    @Override public void onEnable() {
        try {
            if (!Bukkit.getMinecraftVersion().equals("26.1.2")) {
                throw new IllegalStateException("GameHeist requires Minecraft 26.1.2; found " + Bukkit.getMinecraftVersion());
            }
            saveDefaultConfig();
            var arenaDirectory = getDataFolder().toPath().resolve("arenas");
            Files.createDirectories(arenaDirectory);
            if (!Files.exists(arenaDirectory.resolve("graybox.yml"))) saveResource("arenas/graybox.yml", false);
            if (!Files.exists(arenaDirectory.resolve("graybox-v2.yml"))) saveResource("arenas/graybox-v2.yml", false);
            if (!Files.exists(arenaDirectory.resolve("graybox-v3.yml"))) saveResource("arenas/graybox-v3.yml", false);
            if (!Files.exists(arenaDirectory.resolve("graybox-v4.yml"))) saveResource("arenas/graybox-v4.yml", false);
            var arenas = ArenaLoader.load(arenaDirectory);
            var fallback = Bukkit.getWorld(Objects.requireNonNull(getConfig().getString("instances.fallback-world")));
            if (fallback == null || fallback.getName().startsWith("heist_")) {
                throw new IllegalArgumentException("Fallback must be an existing non-instance world");
            }
            packs = new ResourcePackGate(this);
            var players = new PlayerSessions(fallback, packs.modelsEnabled());
            worlds = new PaperWorldGateway(players, getLogger());
            var results = new InMemoryResultRepository(100);
            ProfileRepository profileRepository;
            ResultRepository resultRepository;
            String storageMode = getConfig().getString("storage.mode", "memory");
            if (storageMode.equals("mongodb")) {
                storage = new MongoStore(System.getenv("HEIST_MONGODB_URI"), getConfig().getString("storage.database", "gameheist"));
                profileRepository = storage;
                resultRepository = result -> storage.save(result).thenCompose(ignored -> results.save(result));
            } else if (storageMode.equals("memory")) {
                getLogger().warning("Memory storage: profiles and results are lost on restart.");
                profileRepository = new InMemoryProfileRepository();
                resultRepository = results;
            } else throw new IllegalArgumentException("storage.mode must be memory or mongodb");
            var profiles = new ProfileSessions(profileRepository, LoadoutCatalog.starter());
            instances = new InstanceManager(arenas, worlds, resultRepository, Clock.systemUTC(), LoadoutCatalog.starter(),
                    getConfig().getInt("instances.maximum"));
            drain = new DrainController(instances, profiles, worlds::cleanupHealthy);
            var audio = new dev.gameheist.paper.pack.HeistAudio(players, profiles::soundEnabled);
            var gameplay = new HeistGameplay(instances, arenas, players, audio, profiles);
            var guards = new GuardController(instances, arenas, players, getLogger());
            var combat = new CombatController(instances, players, guards, getLogger(), audio, gameplay);
            var statistics = new StatisticsView(storage != null ? storage : results, storage != null);
            var command = Objects.requireNonNull(getCommand("heist"));
            var menus = new dev.gameheist.paper.menu.PlayerMenus(this, profiles, instances, results);
            Bukkit.getPluginManager().registerEvents(menus, this);
            var executor = new HeistCommand(instances, arenas, packs, players, results, getLogger(), guards, profiles, storage != null, statistics, drain, menus);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            Bukkit.getPluginManager().registerEvents(packs, this);
            Bukkit.getPluginManager().registerEvents(guards, this);
            Bukkit.getPluginManager().registerEvents(combat, this);
            Bukkit.getPluginManager().registerEvents(statistics, this);
            Bukkit.getPluginManager().registerEvents(new ProfileListener(profiles), this);
            Bukkit.getPluginManager().registerEvents(new PracticeListener(instances, arenas, players, getLogger(), gameplay), this);
            if (getConfig().getBoolean("health.enabled")) {
                health = new HealthServer(new InetSocketAddress(
                        Objects.requireNonNull(getConfig().getString("health.bind-address")), getConfig().getInt("health.port")),
                        Duration.ofSeconds(getConfig().getLong("health.stale-after-seconds")), System::nanoTime,
                        System.getenv("HEIST_DRAIN_TOKEN"));
            }
            if (getConfig().getBoolean("metrics.enabled", false)) {
                try {
                    metrics = new InfluxMetricsExporter(URI.create(getConfig().getString("metrics.url", "http://127.0.0.1:18086")),
                            getConfig().getString("metrics.organization", "gameheist"), getConfig().getString("metrics.bucket", "heist_metrics"),
                            System.getenv("HEIST_INFLUX_TOKEN"), getLogger()::warning);
                    metricsSampler = new PaperMetrics(metrics, instances, drain, System.getenv().getOrDefault(
                            "HEIST_SERVER_ID", getConfig().getString("metrics.server-id", "local-1")));
                } catch (RuntimeException invalidConfiguration) {
                    if (metrics != null) metrics.close();
                    metrics = null;
                    getLogger().warning("Metrics disabled: invalid configuration. Check metrics settings and HEIST_INFLUX_TOKEN.");
                }
            }
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                long tickStarted = System.nanoTime();
                if (health != null && health.drainRequested()) drain.drain();
                profiles.tick();
                menus.tick();
                statistics.tick();
                guards.tick();
                combat.tick();
                gameplay.tick();
                instances.tick();
                if (health != null) {
                    int active = instances.all().size();
                    health.publish(worlds.accepting() && !instances.draining()
                            && active < getConfig().getInt("instances.maximum"), drain.status());
                }
                if (metricsSampler != null) {
                    try { metricsSampler.tick(System.nanoTime() - tickStarted); }
                    catch (RuntimeException samplingFailure) {
                        metrics.close();
                        metricsSampler = null;
                        getLogger().warning("Metrics sampling disabled after an error; gameplay continues.");
                    }
                }
            }, 1, 1);
            Bukkit.getOnlinePlayers().forEach(packs::request);
            Bukkit.getOnlinePlayers().forEach(player -> profiles.open(player.getUniqueId()));
            getLogger().info("GameHeist foundation enabled: " + arenas.all().size() + " arena version(s); PRACTICE ONLY.");
        } catch (Exception failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "GameHeist initialization failed", failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (health != null) health.close();
        if (metrics != null) metrics.close();
        if (packs != null) packs.close();
        if (drain != null) {
            drain.drain();
            var status = drain.status();
            if (status.pendingProfileOperations() > 0 || status.unacknowledgedProfileWrites() > 0) {
                getLogger().warning("Stopping with pending profile operations=" + status.pendingProfileOperations()
                        + "; unacknowledged profile writes=" + status.unacknowledgedProfileWrites());
            }
        }
        if (instances != null) instances.shutdown().forEach(getLogger()::warning);
        if (storage != null) storage.close();
        if (worlds != null) worlds.shutdown();
    }
}
