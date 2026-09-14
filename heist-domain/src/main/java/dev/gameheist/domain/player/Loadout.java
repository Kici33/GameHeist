package dev.gameheist.domain.player;

import dev.gameheist.domain.Checks;
import java.util.Objects;

/** Equipment identifiers are content references, not client-provided item stacks. */
public record Loadout(Role role, String weaponId, String gadgetId) {
    public Loadout {
        Objects.requireNonNull(role);
        Checks.id(weaponId);
        Checks.id(gadgetId);
    }
    public static Loadout starter(Role role) { return new Loadout(role, "carbine", "medkit"); }
}
