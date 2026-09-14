package dev.gameheist.paper.gameplay;

import java.util.*;
import java.util.stream.Collectors;

/** Compact, deterministic crew summary: downed teammates appear before healthy teammates. */
public final class CrewStatus {
    private CrewStatus() { }
    public record Member(UUID id, String name, int health, boolean present) { }
    public static boolean needsRescue(UUID viewer, Collection<Member> members) {
        return members.stream().anyMatch(member -> !member.id().equals(viewer) && member.present() && member.health() == 0);
    }
    public static float healthFraction(UUID viewer, Collection<Member> members) {
        return (float) members.stream().filter(member -> !member.id().equals(viewer))
                .mapToDouble(member -> member.present() ? Math.clamp(member.health(), 0, 100) / 100.0 : 0)
                .average().orElse(0);
    }
    public static String format(UUID viewer, Collection<Member> members) {
        String teammates = members.stream().filter(member -> !member.id().equals(viewer))
                .sorted(Comparator.comparingInt((Member member) -> member.present() && member.health() == 0 ? 0 : 1)
                        .thenComparing(Member::name).thenComparing(Member::id))
                .map(member -> member.name() + ": " + (!member.present() ? "AWAY"
                        : member.health() == 0 ? "DOWN" : member.health() + " HP"))
                .collect(Collectors.joining(" | "));
        return teammates.isEmpty() ? "" : "Crew — " + teammates;
    }
}
