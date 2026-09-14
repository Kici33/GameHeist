package dev.gameheist.domain.arena;

import dev.gameheist.domain.match.MatchPhase;
import dev.gameheist.domain.objective.ObjectiveDefinition;
import dev.gameheist.domain.objective.HeistDefinition;
import dev.gameheist.domain.npc.GuardDefinition;
import java.time.Duration;
import java.util.*;

/** Immutable published content; mutable objective state belongs to each Match. */
public record ArenaDefinition(ArenaKey key, String displayName, Bounds bounds, Position spawn,
                              int capacity, Duration timeLimit, List<ObjectiveDefinition> objectives,
                              Optional<HeistDefinition> heist, List<GuardDefinition> guards, List<BlockPosition> cover) {
    public ArenaDefinition(ArenaKey key, String displayName, Bounds bounds, Position spawn, int capacity,
                           Duration timeLimit, List<ObjectiveDefinition> objectives, Optional<HeistDefinition> heist,
                           List<GuardDefinition> guards) {
        this(key, displayName, bounds, spawn, capacity, timeLimit, objectives, heist, guards, List.of());
    }
    public ArenaDefinition(ArenaKey key, String displayName, Bounds bounds, Position spawn, int capacity,
                           Duration timeLimit, List<ObjectiveDefinition> objectives, Optional<HeistDefinition> heist) {
        this(key, displayName, bounds, spawn, capacity, timeLimit, objectives, heist, List.of());
    }
    public ArenaDefinition(ArenaKey key, String displayName, Bounds bounds, Position spawn, int capacity,
                           Duration timeLimit, List<ObjectiveDefinition> objectives) {
        this(key, displayName, bounds, spawn, capacity, timeLimit, objectives, Optional.empty());
    }
    public ArenaDefinition {
        Objects.requireNonNull(key);
        if (Objects.requireNonNull(displayName).isBlank()) throw new IllegalArgumentException("Blank arena name");
        Objects.requireNonNull(bounds);
        Objects.requireNonNull(spawn);
        if (!bounds.contains(spawn)) throw new IllegalArgumentException("Spawn is outside arena bounds");
        if (capacity < 1 || capacity > 4) throw new IllegalArgumentException("Capacity must be 1–4");
        Objects.requireNonNull(timeLimit);
        if (timeLimit.isZero() || timeLimit.isNegative()) throw new IllegalArgumentException("Time limit must be positive");
        objectives = List.copyOf(objectives);
        Objects.requireNonNull(heist);
        cover = List.copyOf(cover);
        if (cover.size() > 256 || new HashSet<>(cover).size() != cover.size()) {
            throw new IllegalArgumentException("Cover requires at most 256 distinct blocks");
        }
        for (BlockPosition block : cover) {
            if (!bounds.contains(block.center())) throw new IllegalArgumentException("Cover outside arena");
            if (blocksStandingPosition(block, spawn)) throw new IllegalArgumentException("Cover obstructs crew spawn");
            if (heist.isPresent() && heist.orElseThrow().allPositions().contains(block)) {
                throw new IllegalArgumentException("Cover overlaps an objective");
            }
        }
        guards = List.copyOf(guards);
        if (guards.size() > 24) throw new IllegalArgumentException("Arena supports at most 24 guards");
        Set<String> guardIds = new HashSet<>();
        for (GuardDefinition guard : guards) {
            if (!guardIds.add(guard.id())) throw new IllegalArgumentException("Duplicate guard: " + guard.id());
            if (guard.patrol().stream().anyMatch(point -> !bounds.contains(point))) {
                throw new IllegalArgumentException("Guard route outside arena: " + guard.id());
            }
            for (Position point : guard.patrol()) {
                for (BlockPosition block : cover) {
                    if (blocksStandingPosition(block, point)) throw new IllegalArgumentException("Cover obstructs guard route");
                }
                if (heist.isPresent() && heist.orElseThrow().allPositions().stream()
                        .anyMatch(block -> blocksStandingPosition(block, point))) {
                    throw new IllegalArgumentException("Objective obstructs guard route");
                }
            }
        }
        if (heist.isPresent()) heist.orElseThrow().validate(bounds, objectives);
        Map<String, ObjectiveDefinition> byId = new HashMap<>();
        for (var objective : objectives) {
            if (byId.putIfAbsent(objective.id(), objective) != null) {
                throw new IllegalArgumentException("Duplicate objective: " + objective.id());
            }
        }
        for (var objective : objectives) {
            for (var prerequisite : objective.prerequisites()) {
                var dependency = byId.get(prerequisite);
                if (dependency == null) throw new IllegalArgumentException("Missing prerequisite: " + prerequisite);
                if (dependency.phase().ordinal() > objective.phase().ordinal()) {
                    throw new IllegalArgumentException("Prerequisite belongs to a later phase: " + prerequisite);
                }
                if (dependency.phase() != objective.phase() && !dependency.required()) {
                    throw new IllegalArgumentException("Earlier-phase prerequisite must be required: " + prerequisite);
                }
            }
        }
        Set<String> visited = new HashSet<>();
        for (var objective : objectives) visit(objective.id(), byId, visited, new HashSet<>());
        for (var phase : List.of(MatchPhase.INFILTRATION, MatchPhase.VAULT, MatchPhase.EXTRACTION)) {
            if (objectives.stream().noneMatch(o -> o.phase() == phase && o.required())) {
                throw new IllegalArgumentException("Phase needs a required objective: " + phase);
            }
        }
    }

    private static boolean blocksStandingPosition(BlockPosition block, Position position) {
        return block.x() == (int) Math.floor(position.x()) && block.z() == (int) Math.floor(position.z())
                && (block.y() == (int) Math.floor(position.y()) || block.y() == (int) Math.floor(position.y()) + 1);
    }

    private static void visit(String id, Map<String, ObjectiveDefinition> definitions,
                              Set<String> visited, Set<String> visiting) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Cyclic objective graph at " + id);
        for (String dependency : definitions.get(id).prerequisites()) visit(dependency, definitions, visited, visiting);
        visiting.remove(id);
        visited.add(id);
    }
}
