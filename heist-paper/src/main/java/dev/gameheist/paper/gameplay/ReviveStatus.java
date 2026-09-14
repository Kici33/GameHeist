package dev.gameheist.paper.gameplay;

import dev.gameheist.domain.combat.CombatSnapshot;
import java.util.*;

/** Derives recipient feedback from current server state, without a separate progress timer. */
public final class ReviveStatus {
    private ReviveStatus() { }
    public static String forRecipient(UUID recipient, CombatSnapshot combat, Map<UUID, String> names) {
        var target = combat.players().get(recipient);
        if (target == null || !target.downed()) return "";
        return combat.players().entrySet().stream()
                .filter(entry -> !entry.getValue().downed() && entry.getValue().reviving().filter(recipient::equals).isPresent())
                .min(Comparator.<Map.Entry<UUID, CombatSnapshot.Player>>comparingLong(entry -> entry.getValue().reviveMillis())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> "Being revived by " + names.getOrDefault(entry.getKey(), "teammate") + " · "
                        + ((entry.getValue().reviveMillis() + 999) / 1000) + "s")
                .orElse("DOWNED — wait for a teammate to revive you");
    }
}
