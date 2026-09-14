package dev.gameheist.paper.config;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.MatchPhase;
import dev.gameheist.domain.objective.*;
import dev.gameheist.domain.npc.GuardDefinition;
import dev.gameheist.runtime.arena.ArenaRegistry;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class ArenaLoader {
    private ArenaLoader() {}
    public static ArenaRegistry load(Path directory) throws IOException {
        var registry = new ArenaRegistry();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                try {
                    var yaml = new YamlConfiguration();
                    yaml.load(path.toFile());
                    List<ObjectiveDefinition> objectives = new ArrayList<>();
                    for (Map<?, ?> item : yaml.getMapList("objectives")) {
                        Object dependencies = item.get("prerequisites");
                        if (!(dependencies instanceof List<?> list)) throw new IllegalArgumentException("Expected prerequisites list");
                        Set<String> prerequisites = new HashSet<>();
                        for (Object value : list) {
                            if (!(value instanceof String string)) throw new IllegalArgumentException("Prerequisite must be text");
                            prerequisites.add(string);
                        }
                        if (!(item.get("required") instanceof Boolean required)) throw new IllegalArgumentException("Expected required boolean");
                        objectives.add(new ObjectiveDefinition(text(item.get("id")), ObjectiveType.valueOf(text(item.get("type"))),
                                MatchPhase.valueOf(text(item.get("phase"))), prerequisites, required));
                    }
                    registry.register(new ArenaDefinition(new ArenaKey(text(yaml.get("id")), integer(yaml.get("version"))),
                            text(yaml.get("name")), new Bounds(position(yaml.getList("bounds.min")),
                            position(yaml.getList("bounds.max"))), position(yaml.getList("spawn")),
                            integer(yaml.get("capacity")), Duration.ofSeconds(integer(yaml.get("time-limit-seconds"))),
                            objectives, heist(yaml), guards(yaml), cover(yaml), combat(yaml)));
                } catch (Exception failure) {
                    throw new IOException("Invalid arena " + path.getFileName() + ": " + failure.getMessage(), failure);
                }
            }
        }
        if (registry.all().isEmpty()) throw new IOException("No arena manifests found");
        return registry;
    }
    private static String text(Object value) {
        if (!(value instanceof String string) || string.isBlank()) throw new IllegalArgumentException("Expected nonblank text");
        return string;
    }
    private static boolean combat(YamlConfiguration yaml) {
        if (!yaml.contains("combat")) return false;
        if (!(yaml.get("combat") instanceof Boolean enabled)) throw new IllegalArgumentException("Expected combat boolean");
        return enabled;
    }
    private static Optional<HeistDefinition> heist(YamlConfiguration yaml) {
        if (!yaml.contains("heist")) return Optional.empty();
        if (!yaml.isConfigurationSection("heist")) throw new IllegalArgumentException("Expected heist section");
        List<BlockPosition> bags = new ArrayList<>();
        var rawBags = yaml.getList("heist.loot.blocks");
        if (rawBags == null) throw new IllegalArgumentException("Expected loot blocks list");
        for (Object raw : rawBags) bags.add(block(raw));
        return Optional.of(new HeistDefinition(text(yaml.get("heist.security.objective")), block(yaml.get("heist.security.block")),
                text(yaml.get("heist.drill.objective")), block(yaml.get("heist.drill.block")),
                Duration.ofSeconds(integer(yaml.get("heist.drill.duration-seconds"))),
                Duration.ofSeconds(integer(yaml.get("heist.drill.repair-seconds"))), integer(yaml.get("heist.drill.jams")),
                text(yaml.get("heist.loot.objective")), bags, integer(yaml.get("heist.loot.required-bags")),
                text(yaml.get("heist.extraction.objective")), block(yaml.get("heist.extraction.block")),
                Duration.ofSeconds(integer(yaml.get("heist.extraction.duration-seconds")))));
    }
    private static List<GuardDefinition> guards(YamlConfiguration yaml) {
        if (!yaml.contains("guards")) return List.of();
        var raw = yaml.getList("guards");
        if (raw == null) throw new IllegalArgumentException("Expected guards list");
        List<GuardDefinition> guards = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Map<?, ?> guard) || !(guard.get("patrol") instanceof List<?> route)) {
                throw new IllegalArgumentException("Guard requires a patrol list");
            }
            List<Position> patrol = new ArrayList<>();
            for (Object point : route) {
                if (!(point instanceof List<?> coordinates)) throw new IllegalArgumentException("Invalid patrol point");
                patrol.add(position(coordinates));
            }
            guards.add(new GuardDefinition(text(guard.get("id")), patrol, number(guard.get("sight-range")),
                    number(guard.get("field-of-view")), Duration.ofSeconds(integer(guard.get("detection-seconds"))),
                    Duration.ofSeconds(integer(guard.get("alarm-seconds"))), Duration.ofSeconds(integer(guard.get("search-seconds"))),
                    number(guard.get("speed"))));
        }
        return List.copyOf(guards);
    }
    private static List<BlockPosition> cover(YamlConfiguration yaml) {
        if (!yaml.contains("cover")) return List.of();
        var raw = yaml.getList("cover");
        if (raw == null) throw new IllegalArgumentException("Expected cover block list");
        return raw.stream().map(ArenaLoader::block).toList();
    }
    private static double number(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Expected a finite number");
        }
        return number.doubleValue();
    }
    private static BlockPosition block(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() != 3) throw new IllegalArgumentException("Block requires three integers");
        return new BlockPosition(integer(values.get(0)), integer(values.get(1)), integer(values.get(2)));
    }
    private static int integer(Object raw) {
        if (!(raw instanceof Integer value)) throw new IllegalArgumentException("Expected an integer, got: " + raw);
        return value;
    }
    private static Position position(List<?> values) {
        if (values == null || values.size() != 3 || values.stream().anyMatch(v -> !(v instanceof Number))) {
            throw new IllegalArgumentException("Position requires three numbers");
        }
        return new Position(((Number) values.get(0)).doubleValue(), ((Number) values.get(1)).doubleValue(),
                ((Number) values.get(2)).doubleValue(), 0, 0);
    }
}
