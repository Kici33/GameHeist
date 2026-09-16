package dev.gameheist.runtime.network;

import java.time.*;
import java.util.*;

/** Owner-thread lobby parties. Invitations need consent; only queued snapshots become durable. */
public final class LobbyCrews {
    private final Clock clock;
    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, Invite> invitations = new HashMap<>();
    public LobbyCrews(Clock clock) { this.clock = clock; }
    public void invite(UUID leader, UUID target) {
        if (leader.equals(target)) throw new IllegalArgumentException("Cannot invite yourself");
        var party = party(leader);
        if (!party.leader.equals(leader) || party.locked || party.members.size() == 4) throw new IllegalStateException("Crew is locked/full or you are not leader");
        if (parties.containsKey(target)) throw new IllegalStateException("Player already belongs to a crew");
        invitations.entrySet().removeIf(e -> !e.getValue().until.isAfter(clock.instant()));
        if (invitations.size() >= 128) throw new IllegalStateException("Too many pending invitations");
        invitations.put(target, new Invite(leader, clock.instant().plusSeconds(30)));
    }
    public void accept(UUID player) {
        var invitation = invitations.remove(player);
        if (invitation == null || !invitation.until.isAfter(clock.instant())) throw new IllegalStateException("Invitation expired");
        var party = parties.get(invitation.leader);
        if (party == null || !party.leader.equals(invitation.leader) || party.locked || party.members.size() == 4 || parties.containsKey(player))
            throw new IllegalStateException("Crew no longer available");
        party.members.add(player); parties.put(player, party);
    }
    public Set<UUID> lock(UUID leader) {
        var party = party(leader);
        if (!party.leader.equals(leader) || party.locked) throw new IllegalStateException("Only an unqueued crew leader can queue");
        party.locked = true; return Set.copyOf(party.members);
    }
    public void unlock(UUID member) { var party = parties.get(member); if (party != null) party.locked = false; }
    public Set<UUID> members(UUID member) {
        var party = parties.get(member); return party == null ? Set.of(member) : Set.copyOf(party.members);
    }
    public void leave(UUID player) {
        var party = parties.get(player);
        if (party == null) return;
        if (party.locked) throw new IllegalStateException("Cancel the queued crew first");
        parties.remove(player); party.members.remove(player);
        if (party.leader.equals(player)) {
            for (UUID member : party.members) parties.remove(member);
            invitations.entrySet().removeIf(e -> e.getValue().leader.equals(player));
        }
    }
    public void forget(UUID member) {
        var party = parties.get(member);
        if (party != null) {
            party.members.forEach(parties::remove);
            invitations.entrySet().removeIf(e -> e.getValue().leader.equals(party.leader));
        }
        invitations.remove(member);
    }
    private Party party(UUID leader) {
        return parties.computeIfAbsent(leader, id -> new Party(id));
    }
    private static final class Party {
        private final UUID leader;
        private final Set<UUID> members = new LinkedHashSet<>();
        private boolean locked;
        private Party(UUID leader) { this.leader = leader; members.add(leader); }
    }
    private record Invite(UUID leader, Instant until) { }
}
