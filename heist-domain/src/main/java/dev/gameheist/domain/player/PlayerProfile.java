package dev.gameheist.domain.player;

import java.util.*;

/** Revision is an optimistic concurrency token; match loadouts are immutable snapshots. */
public record PlayerProfile(UUID playerId, long revision, List<Loadout> presets,
                            int selectedPreset, PlayerSettings settings) {
    public PlayerProfile {
        Objects.requireNonNull(playerId);
        if (revision < 0) throw new IllegalArgumentException("Negative profile revision");
        presets = List.copyOf(presets);
        if (presets.isEmpty() || presets.size() > 3 || selectedPreset < 0 || selectedPreset >= presets.size()) {
            throw new IllegalArgumentException("Profile requires 1–3 presets and a valid selection");
        }
        Objects.requireNonNull(settings);
    }
    public Loadout selectedLoadout() { return presets.get(selectedPreset); }
    public static PlayerProfile starter(UUID playerId) {
        return new PlayerProfile(playerId, 0, List.of(Loadout.starter(Role.TECHNICIAN)), 0, PlayerSettings.defaults());
    }
}
