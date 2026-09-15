package dev.gameheist.domain.player;

import dev.gameheist.domain.Checks;
import java.util.Objects;

/** Equipment identifiers are content references, not client-provided item stacks. */
public record Loadout(Role role, String weaponId, String gadgetId, String name, String cosmeticId) {
    public Loadout(Role role, String weaponId, String gadgetId) {
        this(role, weaponId, gadgetId, "Preset", "default");
    }
    public Loadout {
        Objects.requireNonNull(role);
        Checks.id(weaponId);
        Checks.id(gadgetId);
        Checks.id(cosmeticId);
        name = Objects.requireNonNull(name).strip();
        if (name.isEmpty() || name.length() > 24 || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 167))
            throw new IllegalArgumentException("Preset name must contain 1–24 printable characters");
    }
    public static Loadout starter(Role role) { return new Loadout(role, "carbine", "medkit"); }
}
