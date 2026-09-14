package dev.gameheist.runtime.metrics;

import java.util.*;

/** Bounded numeric snapshot; no player or match identifiers belong in metrics. */
public record MetricsSnapshot(long timestampMillis, String serverId, Map<String, Number> fields) {
    public MetricsSnapshot {
        if (timestampMillis < 0) throw new IllegalArgumentException("Negative metrics timestamp");
        if (serverId == null || !serverId.matches("[a-zA-Z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid metrics server ID");
        fields = Map.copyOf(fields);
        if (fields.isEmpty() || fields.size() > 64) throw new IllegalArgumentException("Metrics require 1-64 fields");
        fields.forEach((key, value) -> {
            if (!key.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid metric field");
            if (!(value instanceof Long) && !(value instanceof Double)) throw new IllegalArgumentException("Metrics must use Long or Double");
            if (value instanceof Double number && !Double.isFinite(number)) throw new IllegalArgumentException("Non-finite metric");
        });
    }
    public String lineProtocol() {
        var line = new StringBuilder("heist_server,server=").append(serverId).append(' ');
        var joiner = new StringJoiner(",");
        fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> joiner.add(
                entry.getKey() + "=" + entry.getValue() + (entry.getValue() instanceof Long ? "i" : "")));
        return line.append(joiner).append(' ').append(timestampMillis).append('\n').toString();
    }
}
