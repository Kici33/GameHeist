package dev.gameheist.domain.player;

/** Successful security disable, drill installation/repair and bag deposit; never clicks or pickup retries. */
public record ObjectiveStats(long actions, long securedBags) {
    public ObjectiveStats {
        if (actions < 0 || securedBags < 0 || securedBags > actions) throw new IllegalArgumentException("Invalid objective contribution");
    }
    public static ObjectiveStats empty() { return new ObjectiveStats(0, 0); }
    public ObjectiveStats add(ObjectiveStats other) {
        return new ObjectiveStats(Math.addExact(actions, other.actions), Math.addExact(securedBags, other.securedBags));
    }
}
