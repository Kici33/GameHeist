package dev.gameheist.runtime.instance;

import dev.gameheist.domain.match.MatchSnapshot;
import java.util.Optional;

public record InstanceSnapshot(MatchSnapshot match, String worldName,
                               InstanceState state, Optional<String> lastError) {}
