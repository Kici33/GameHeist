package dev.gameheist.domain.objective;

import dev.gameheist.domain.arena.*;
import dev.gameheist.domain.match.Match;
import dev.gameheist.domain.player.Role;
import java.time.*;
import java.util.*;

/** Owned by one match. Positions must come from its trusted server adapter, never client payloads. */
public final class HeistRun {
    private final Match match;
    private final HeistDefinition definition;
    private final Clock clock;
    private final long[] jamThresholds;
    private final Map<UUID, BlockPosition> carried = new HashMap<>();
    private final Set<BlockPosition> unavailable = new HashSet<>();
    private final Set<UUID> votes = new HashSet<>();
    private boolean securityDisabled, started, complete, jammed;
    private int jamsTriggered, secured;
    private long drillWorkMillis;
    private Instant lastTick, repairDeadline, extractionDeadline;
    private UUID repairingPlayer;

    public HeistRun(Match match, HeistDefinition definition, Clock clock, long seed) {
        this.match = Objects.requireNonNull(match);
        this.definition = Objects.requireNonNull(definition);
        this.clock = Objects.requireNonNull(clock);
        var random = new Random(seed);
        jamThresholds = new long[definition.jams()];
        for (int i = 0; i < jamThresholds.length; i++) {
            double fraction = (i == 0 ? 0.25 : 0.60) + random.nextDouble() * 0.1;
            jamThresholds[i] = (long) (definition.drillDuration().toMillis() * fraction);
        }
    }

    public String interact(UUID player, BlockPosition block, Position position) {
        if (!match.hasParticipant(player)) throw new IllegalStateException("Player is not in this crew");
        if (!block.within(position, 5)) throw new IllegalStateException("Move closer to the objective");
        if (block.equals(definition.security())) {
            if (securityDisabled) return "Security is already disabled.";
            match.completeObjective(definition.securityObjective());
            securityDisabled = true;
            return "Security disabled. Install the drill at the orange marker.";
        }
        if (block.equals(definition.drill())) {
            if (!securityDisabled) throw new IllegalStateException("Disable security first");
            if (complete) return "Vault open. Collect a gold bag and bring it to extraction.";
            if (!started) {
                started = true;
                lastTick = clock.instant();
                return "Drill installed. Stay ready to repair jams.";
            }
            if (jammed) {
                if (repairingPlayer != null) return "A crewmate is repairing the drill.";
                repairingPlayer = player;
                long millis = definition.repairDuration().toMillis();
                if (match.loadout(player).role() == Role.TECHNICIAN) millis = millis * 3 / 4;
                repairDeadline = clock.instant().plusMillis(millis);
                return "Repairing. Stay within five blocks of the drill.";
            }
            return "The drill is running.";
        }
        if (definition.bags().contains(block)) {
            if (!complete) throw new IllegalStateException("Wait for the drill to open the vault");
            if (carried.containsKey(player)) throw new IllegalStateException("Deliver your current bag first");
            if (!unavailable.add(block)) return "That bag has already been taken.";
            carried.put(player, block);
            return "Carrying one bag. Bring it to the green extraction marker.";
        }
        if (block.equals(definition.extraction())) {
            if (carried.remove(player) != null) {
                secured++;
                if (secured == definition.requiredBags()) match.completeObjective(definition.lootObjective());
                return "Bag secured (" + secured + "/" + definition.requiredBags()
                        + "). Click again to vote for extraction once enough bags are secured.";
            }
            if (secured < definition.requiredBags()) throw new IllegalStateException("Secure more bags before extracting");
            if (extractionDeadline != null) return "Extraction is counting down. Stay near the green marker.";
            votes.add(player);
            if (votes.size() > match.activeParticipantCount() / 2) {
                extractionDeadline = clock.instant().plus(definition.extractionDuration());
                return "Crew majority reached. Extraction started; gather at the green marker.";
            }
            return "Extraction vote recorded (" + votes.size() + "/" + (match.activeParticipantCount() / 2 + 1) + ").";
        }
        return "Use the labeled security, drill, loot, or extraction blocks.";
    }

    public void tick(Map<UUID, Position> presentPlayers) {
        Instant now = clock.instant();
        if (started && !complete) {
            long elapsed = Math.max(0, Duration.between(lastTick, now).toMillis());
            lastTick = now;
            if (jammed) {
                if (repairingPlayer != null) {
                    Position position = presentPlayers.get(repairingPlayer);
                    if (position == null || !definition.drill().within(position, 5)) {
                        repairingPlayer = null;
                        repairDeadline = null;
                    } else if (!now.isBefore(repairDeadline)) {
                        jammed = false;
                        repairingPlayer = null;
                        repairDeadline = null;
                    }
                }
            } else {
                drillWorkMillis = Math.min(definition.drillDuration().toMillis(), drillWorkMillis + elapsed);
                if (jamsTriggered < jamThresholds.length && drillWorkMillis >= jamThresholds[jamsTriggered]) {
                    drillWorkMillis = jamThresholds[jamsTriggered++];
                    jammed = true;
                } else if (drillWorkMillis >= definition.drillDuration().toMillis()) {
                    match.completeObjective(definition.drillObjective());
                    complete = true;
                }
            }
        }
        if (extractionDeadline != null && !now.isBefore(extractionDeadline)) {
            boolean someonePresent = presentPlayers.entrySet().stream().anyMatch(entry ->
                    match.hasParticipant(entry.getKey()) && definition.extraction().within(entry.getValue(), 5));
            if (someonePresent) match.completeObjective(definition.extractionObjective());
            else {
                extractionDeadline = null;
                votes.clear();
            }
        }
    }

    /** Return carried loot to its authored marker exactly once; it can be collected again. */
    public void incapacitate(UUID player) {
        BlockPosition bag = carried.remove(player);
        if (bag != null) unavailable.remove(bag);
        votes.remove(player);
        if (player.equals(repairingPlayer)) { repairingPlayer = null; repairDeadline = null; }
    }

    public HeistSnapshot snapshot() {
        return new HeistSnapshot(securityDisabled, started, complete, jammed, jamsTriggered,
                Math.max(0, definition.drillDuration().toMillis() - drillWorkMillis),
                Optional.ofNullable(repairingPlayer), remaining(repairDeadline), carried, unavailable, secured,
                definition.requiredBags(), votes.size(), extractionDeadline != null, remaining(extractionDeadline));
    }
    private long remaining(Instant deadline) {
        return deadline == null ? 0 : Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
    }
}
