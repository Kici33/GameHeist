package dev.gameheist.runtime.npc;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.runtime.instance.ManagedResource;
import java.util.UUID;

public interface GuardActor extends ManagedResource {
    boolean alive();
    Position position();
    Position eyePosition();
    boolean canSee(UUID playerId);
    boolean moveTo(Position position, double speed);
    void stop();
    void lookAt(Position position);
    void label(String text);
}
