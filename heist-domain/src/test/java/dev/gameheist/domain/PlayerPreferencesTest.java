package dev.gameheist.domain;

import dev.gameheist.domain.player.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPreferencesTest {
    @Test void changingSoundPreservesOtherPreferences() {
        var settings = new PlayerSettings("pl", true, true, true, false);
        assertEquals(new PlayerSettings("pl", false, true, true, false), settings.change("sound", "off"));
        assertEquals(new PlayerSettings("en", true, true, true, false), settings.change("language", "en"));
        assertThrows(IllegalArgumentException.class, () -> settings.change("language", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> settings.change("motion", "maybe"));
    }

    @Test void rejectsUnknownCosmeticsWrongEquipmentSlotsAndMalformedNames() {
        var catalog = LoadoutCatalog.starter();
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(new Loadout(Role.SCOUT, "medkit", "carbine")));
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(new Loadout(Role.SCOUT, "carbine", "medkit", "Scout", "locked")));
        for (String name : List.of(" ", "x".repeat(25), "bad\nname", "§ared"))
            assertThrows(IllegalArgumentException.class, () -> new Loadout(Role.SCOUT, "carbine", "medkit", name, "default"));
    }

    @Test void profileCopiesPresetListAndKeepsCapturedLoadoutImmutable() {
        var presets = new ArrayList<>(List.of(new Loadout(Role.SCOUT, "carbine", "medkit", "Original", "default")));
        var profile = new PlayerProfile(UUID.randomUUID(), 0, presets, 0, PlayerSettings.defaults());
        var admitted = profile.selectedLoadout();
        presets.set(0, Loadout.starter(Role.SUPPORT));
        assertEquals("Original", admitted.name());
        assertEquals(admitted, profile.selectedLoadout());
        assertThrows(IllegalArgumentException.class, () -> new PlayerProfile(profile.playerId(), 0,
                Collections.nCopies(4, admitted), 0, profile.settings()));
    }
}
