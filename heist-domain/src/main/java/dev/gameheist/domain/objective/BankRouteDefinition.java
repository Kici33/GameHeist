package dev.gameheist.domain.objective;

import dev.gameheist.domain.arena.*;
import java.time.Duration;
import java.util.*;

/** Authored Brasslock route layered on the stable physical-heist recipe. */
public record BankRouteDefinition(Duration briefingDuration, BlockPosition keycard,
                                  List<BlockPosition> cameras, BlockPosition power,
                                  Duration powerRestoreDuration, BlockPosition escapeActivation,
                                  BlockPosition extractionArea, double extractionRadius) {
    public BankRouteDefinition {
        if (briefingDuration.compareTo(Duration.ofSeconds(5)) < 0 || briefingDuration.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Briefing duration must be 5–120 seconds");
        Objects.requireNonNull(keycard); Objects.requireNonNull(power); Objects.requireNonNull(escapeActivation); Objects.requireNonNull(extractionArea);
        cameras = List.copyOf(cameras);
        if (cameras.isEmpty() || cameras.size() > 8 || new HashSet<>(cameras).size() != cameras.size())
            throw new IllegalArgumentException("Bank route requires 1–8 distinct cameras");
        if (powerRestoreDuration.compareTo(Duration.ofSeconds(1)) < 0 || powerRestoreDuration.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Power restore duration must be 1–30 seconds");
        if (!Double.isFinite(extractionRadius) || extractionRadius < 3 || extractionRadius > 16)
            throw new IllegalArgumentException("Extraction radius must be 3–16 blocks");
        var unique = new HashSet<BlockPosition>();
        unique.add(keycard); unique.add(power); unique.add(escapeActivation); unique.add(extractionArea); unique.addAll(cameras);
        if (unique.size() != cameras.size() + 4) throw new IllegalArgumentException("Bank route anchors must be distinct");
    }
    public List<BlockPosition> allPositions() {
        var result = new ArrayList<BlockPosition>(List.of(keycard, power, escapeActivation, extractionArea));
        result.addAll(cameras);
        return List.copyOf(result);
    }
    public void validate(Bounds bounds, Collection<BlockPosition> occupied) {
        if (allPositions().stream().anyMatch(point -> !bounds.contains(point.center())))
            throw new IllegalArgumentException("Bank route placement outside arena");
        var distinct = new HashSet<>(occupied);
        for (var point : allPositions()) if (!distinct.add(point)) throw new IllegalArgumentException("Bank route overlaps a heist marker: " + point);
    }
}
