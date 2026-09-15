package dev.gameheist.domain.player;

import java.util.Set;

public record LoadoutCatalog(Set<String> weapons, Set<String> gadgets) {
    public LoadoutCatalog {
        weapons = Set.copyOf(weapons);
        gadgets = Set.copyOf(gadgets);
    }
    public void validate(Loadout loadout) {
        if (!weapons.contains(loadout.weaponId()) || !gadgets.contains(loadout.gadgetId()) || !loadout.cosmeticId().equals("default")) {
            throw new IllegalArgumentException("Unknown loadout equipment");
        }
    }
    public static LoadoutCatalog starter() { return new LoadoutCatalog(Set.of("carbine"), Set.of("medkit")); }
}
