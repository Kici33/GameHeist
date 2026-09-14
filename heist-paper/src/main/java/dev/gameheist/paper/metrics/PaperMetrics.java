package dev.gameheist.paper.metrics;

import dev.gameheist.domain.match.AlarmState;
import dev.gameheist.runtime.health.DrainController;
import dev.gameheist.runtime.instance.*;
import dev.gameheist.runtime.metrics.*;
import org.bukkit.Bukkit;
import java.util.*;

/** Paper API reads happen only on the game thread, once per five-second sample window. */
public final class PaperMetrics {
    private final InfluxMetricsExporter exporter;
    private final InstanceManager instances;
    private final DrainController drain;
    private final String serverId;
    private long nextSample = System.nanoTime();
    private long ticks;
    private long totalNanos;
    private long maxNanos;

    public PaperMetrics(InfluxMetricsExporter exporter, InstanceManager instances, DrainController drain, String serverId) {
        // Validate configuration even before the first sample.
        new MetricsSnapshot(0, serverId, Map.of("validation", 0L));
        this.exporter = exporter;
        this.instances = instances;
        this.drain = drain;
        this.serverId = serverId;
    }
    public void tick(long heistTickNanos) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Metrics sampling requires server thread");
        ticks++;
        totalNanos += heistTickNanos;
        maxNanos = Math.max(maxNanos, heistTickNanos);
        long now = System.nanoTime();
        if (now < nextSample) return;
        nextSample = now + 5_000_000_000L;
        var snapshots = instances.all();
        var status = drain.status();
        Map<String, Number> fields = new HashMap<>();
        fields.put("tps_1m", Math.min(20.0, Bukkit.getTPS()[0]));
        fields.put("server_tick_ms", Bukkit.getAverageTickTime());
        fields.put("heist_tick_mean_ms", totalNanos / (double) ticks / 1_000_000.0);
        fields.put("heist_tick_max_ms", maxNanos / 1_000_000.0);
        fields.put("online_players", (long) Bukkit.getOnlinePlayers().size());
        fields.put("active_instances", (long) snapshots.size());
        fields.put("crew_players", snapshots.stream().mapToLong(s -> s.match().participants().size()).sum());
        fields.put("loud_instances", snapshots.stream().filter(s -> s.match().alarm() == AlarmState.LOUD).count());
        for (var state : InstanceState.values()) {
            fields.put("instances_" + state.name().toLowerCase(Locale.ROOT), snapshots.stream().filter(s -> s.state() == state).count());
        }
        fields.put("draining", status.draining() ? 1L : 0L);
        fields.put("safe_to_stop", status.safeToStop() ? 1L : 0L);
        fields.put("pending_profile_operations", (long) status.pendingProfileOperations());
        fields.put("unacknowledged_profile_writes", (long) status.unacknowledgedProfileWrites());
        fields.put("world_cleanup_healthy", status.worldCleanupHealthy() ? 1L : 0L);
        fields.put("export_delivered_total", exporter.delivered());
        fields.put("export_failed_total", exporter.failed());
        fields.put("export_dropped_total", exporter.dropped());
        exporter.publish(new MetricsSnapshot(System.currentTimeMillis(), serverId, fields));
        ticks = totalNanos = maxNanos = 0;
    }
}
