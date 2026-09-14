package dev.gameheist.domain.npc;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.match.AlarmState;
import java.time.Duration;
import java.util.*;

/** One instance per guard. Simulation time is bounded per update to avoid instant detection after lag. */
public final class GuardBrain {
    private final GuardDefinition definition;
    private GuardState state = GuardState.PATROL;
    private double suspicion;
    private long alertMillis, memoryMillis;
    private int patrolIndex = 1;
    private UUID targetId;
    private Position lastKnown;
    private boolean alarmSent;
    private GuardDecision decision = new GuardDecision(GuardState.PATROL, 0, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), false);

    public GuardBrain(GuardDefinition definition) { this.definition = Objects.requireNonNull(definition); }

    public GuardDecision update(Duration elapsed, Position current, Optional<GuardObservation> visible,
                                Optional<Position> noise, AlarmState alarm) {
        Objects.requireNonNull(current);
        Objects.requireNonNull(visible);
        Objects.requireNonNull(noise);
        Objects.requireNonNull(alarm);
        if (elapsed.isNegative() || elapsed.compareTo(Duration.ofMillis(250)) > 0) {
            throw new IllegalArgumentException("Guard updates require 0–250ms of simulation time");
        }
        long millis = elapsed.toMillis();
        if (state == GuardState.RETIRED) return decision;
        boolean raiseAlarm = false;
        Position destination = null, lookAt = null;
        if (visible.isPresent()) {
            var observation = visible.orElseThrow();
            if (!observation.playerId().equals(targetId)) {
                suspicion = 0;
                alertMillis = 0;
                state = GuardState.SUSPICIOUS;
            }
            targetId = observation.playerId();
            lastKnown = observation.position();
            lookAt = lastKnown;
            memoryMillis = definition.searchTime().toMillis();
            if (alarm == AlarmState.LOUD || alarmSent) {
                state = GuardState.PURSUIT;
                destination = lastKnown;
            } else if (state == GuardState.ALERTING) {
                alertMillis += millis;
                if (alertMillis >= definition.alarmTime().toMillis()) {
                    alarmSent = true;
                    raiseAlarm = true;
                    state = GuardState.PURSUIT;
                    destination = lastKnown;
                }
            } else {
                suspicion = Math.min(1, suspicion + millis * observation.suspicionRate() / definition.detectionTime().toMillis());
                if (suspicion >= 1 - 1e-9) {
                    suspicion = 1;
                    state = GuardState.ALERTING;
                    alertMillis = 0;
                } else state = GuardState.SUSPICIOUS;
            }
        } else {
            alertMillis = 0;
            suspicion = Math.max(0, suspicion - millis / (double) definition.detectionTime().toMillis());
            memoryMillis = Math.max(0, memoryMillis - millis);
            if (noise.isPresent() && (state == GuardState.PATROL || targetId == null)) {
                lastKnown = noise.orElseThrow();
                targetId = null;
                memoryMillis = definition.searchTime().toMillis();
            }
            if (lastKnown != null && memoryMillis > 0) {
                state = GuardPerception.distanceSquared(current, lastKnown) <= 1.44
                        ? GuardState.SEARCH : GuardState.INVESTIGATE;
                lookAt = lastKnown;
                if (state == GuardState.INVESTIGATE) destination = lastKnown;
            } else {
                state = GuardState.PATROL;
                targetId = null;
                lastKnown = null;
                suspicion = 0;
                Position waypoint = definition.patrol().get(patrolIndex);
                if (GuardPerception.distanceSquared(current, waypoint) <= 1.44) {
                    patrolIndex = (patrolIndex + 1) % definition.patrol().size();
                }
                destination = definition.patrol().get(patrolIndex);
            }
        }
        decision = new GuardDecision(state, suspicion, Optional.ofNullable(targetId), Optional.ofNullable(lastKnown),
                Optional.ofNullable(destination), Optional.ofNullable(lookAt), raiseAlarm);
        return decision;
    }

    public GuardDecision snapshot() { return decision; }

    public void retire() {
        state = GuardState.RETIRED;
        targetId = null;
        lastKnown = null;
        decision = new GuardDecision(state, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }
}
