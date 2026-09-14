package dev.gameheist.runtime.npc;

import dev.gameheist.domain.npc.GuardDefinition;
import java.io.IOException;

@FunctionalInterface
public interface GuardSpawner {
    GuardActor spawn(GuardDefinition definition) throws IOException;
}
