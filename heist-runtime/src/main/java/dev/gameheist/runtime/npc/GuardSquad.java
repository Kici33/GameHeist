package dev.gameheist.runtime.npc;

import dev.gameheist.domain.arena.Position;
import dev.gameheist.domain.match.AlarmState;
import dev.gameheist.domain.npc.*;
import dev.gameheist.runtime.instance.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Owner-thread squad. Perception is staggered at 5 Hz; each guard requests at most one path per second. */
public final class GuardSquad implements ManagedResource {
    private final List<GuardDefinition> definitions;
    private final Set<UUID> crew;
    private final GuardSpawner spawner;
    private final Runnable raiseAlarm;
    private final Consumer<String> diagnostic;
    private final List<Entry> guards = new ArrayList<>();
    private final ResourceScope resources = new ResourceScope();
    private boolean started, paused, released, alarmRaised;
    private long tick;

    public GuardSquad(List<GuardDefinition> definitions, Set<UUID> crew, GuardSpawner spawner,
                      Runnable raiseAlarm, Consumer<String> diagnostic) {
        this.definitions = List.copyOf(definitions);
        this.crew = Set.copyOf(crew);
        this.spawner = Objects.requireNonNull(spawner);
        this.raiseAlarm = Objects.requireNonNull(raiseAlarm);
        this.diagnostic = Objects.requireNonNull(diagnostic);
        if (definitions.size() > 24 || crew.size() > 4) throw new IllegalArgumentException("Squad limits exceeded");
    }

    /** Register this squad with its instance scope BEFORE start so partial spawns remain owned. */
    public void start() throws IOException {
        if (started || released) throw new IllegalStateException("Squad already started or released");
        started = true;
        reinforce(definitions);
    }

    /** All spawned actors, including partial failures, stay in the same cleanup scope. */
    public void reinforce(List<GuardDefinition> additions) throws IOException {
        if (!started || paused || released) throw new IllegalStateException("Squad cannot accept reinforcements");
        if (guards.size() + additions.size() > 24) throw new IllegalArgumentException("Squad cap exceeded");
        Set<String> ids = new HashSet<>();
        guards.forEach(g -> ids.add(g.definition.id()));
        for (var definition : additions) if (!ids.add(definition.id())) throw new IllegalArgumentException("Duplicate guard ID");
        for (var definition : additions) {
            GuardActor actor = Objects.requireNonNull(spawner.spawn(definition));
            resources.own(actor);
            if (!actor.alive()) throw new IOException("Guard spawn rejected: " + definition.id());
            guards.add(new Entry(definition, actor));
        }
    }

    public void tick(List<GuardPlayer> players, Optional<Position> drillNoise, AlarmState alarm) {
        if (!started || paused || released) return;
        List<GuardPlayer> candidates = players.stream().filter(p -> crew.contains(p.id())).distinct().limit(4).toList();
        for (int index = 0; index < guards.size(); index++) {
            if (index % 4 != tick % 4) continue;
            Entry guard = guards.get(index);
            if (guard.brain.snapshot().state() == GuardState.RETIRED) continue;
            if (!guard.actor.alive()) {
                guard.brain.retire();
                diagnostic.accept("Guard disappeared: " + guard.definition.id());
                continue;
            }
            long start = System.nanoTime();
            update(guard, candidates, drillNoise, alarmRaised ? AlarmState.LOUD : alarm);
            guard.updates++;
            guard.updateMicros = (System.nanoTime() - start) / 1_000;
        }
        tick++;
    }

    private void update(Entry guard, List<GuardPlayer> candidates, Optional<Position> drillNoise, AlarmState alarm) {
        Position current = guard.actor.position(), eye = guard.actor.eyePosition();
        UUID previousTarget = guard.brain.snapshot().targetId().orElse(null);
        Optional<GuardObservation> visible = candidates.stream()
                .filter(p -> GuardPerception.inView(eye, p.eye(), guard.definition.sightRange(), guard.definition.fieldOfView()))
                .sorted(Comparator.<GuardPlayer>comparingInt(p -> p.id().equals(previousTarget) ? 0 : 1)
                        .thenComparingDouble(p -> GuardPerception.distanceSquared(eye, p.eye()))
                        .thenComparing(p -> p.id().toString()))
                .filter(p -> guard.actor.canSee(p.id()))
                .map(p -> new GuardObservation(p.id(), p.feet(), p.suspicionRate())).findFirst();
        Optional<Position> noise = drillNoise.filter(p -> GuardPerception.distanceSquared(current, p) <= 144);
        if (noise.isEmpty()) {
            noise = candidates.stream().filter(p -> p.sprinting() && !p.sneaking())
                    .map(GuardPlayer::feet).filter(p -> GuardPerception.distanceSquared(current, p) <= 64)
                    .min(Comparator.comparingDouble(p -> GuardPerception.distanceSquared(current, p)));
        }
        GuardDecision decision = guard.brain.update(Duration.ofMillis(200), current, visible, noise, alarm);
        if (decision.raiseAlarm() && !alarmRaised && alarm != AlarmState.LOUD) {
            raiseAlarm.run();
            alarmRaised = true;
        }
        guard.actor.label(guard.definition.id() + " · " + decision.state() + " · " + Math.round(decision.suspicion() * 100) + "%");
        decision.lookAt().ifPresent(guard.actor::lookAt);
        if (decision.destination().isEmpty()
                || GuardPerception.distanceSquared(current, decision.destination().orElseThrow()) <= 1.44) {
            guard.actor.stop();
            guard.stuckMillis = 0;
            guard.previousPosition = current;
            return;
        }
        if (guard.previousPosition != null && GuardPerception.distanceSquared(current, guard.previousPosition) < 0.01) {
            guard.stuckMillis += 200;
        } else guard.stuckMillis = 0;
        guard.previousPosition = current;
        if (guard.stuckMillis >= 8_000) {
            guard.brain.retire();
            guard.actor.stop();
            try { guard.actor.release(); }
            catch (IOException failure) { diagnostic.accept("Guard retirement cleanup pending: " + failure); }
            diagnostic.accept("Retired stuck guard " + guard.definition.id() + " after bounded path retries");
            return;
        }
        if (tick - guard.lastPathTick >= 20) {
            if (!guard.actor.moveTo(decision.destination().orElseThrow(), guard.definition.speed())) guard.pathFailures++;
            guard.lastPathTick = tick;
        }
    }

    public List<GuardDiagnostic> snapshots() {
        return guards.stream().map(g -> new GuardDiagnostic(g.definition.id(), g.brain.snapshot(),
                g.updates, g.pathFailures, g.updateMicros)).toList();
    }

    public void pause() {
        if (paused || released) return;
        paused = true;
        for (Entry guard : guards) if (guard.actor.alive()) guard.actor.stop();
    }

    @Override public void release() throws IOException {
        paused = true;
        resources.release();
        released = true;
    }

    private static final class Entry {
        private final GuardDefinition definition;
        private final GuardActor actor;
        private final GuardBrain brain;
        private Position previousPosition;
        private long lastPathTick = -20, updates, updateMicros;
        private int stuckMillis, pathFailures;
        private Entry(GuardDefinition definition, GuardActor actor) {
            this.definition = definition;
            this.actor = actor;
            brain = new GuardBrain(definition);
        }
    }
}
