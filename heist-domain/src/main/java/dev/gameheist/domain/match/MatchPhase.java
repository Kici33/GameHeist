package dev.gameheist.domain.match;

public enum MatchPhase {
    BRIEFING, INFILTRATION, VAULT, EXTRACTION, FINALIZING, CLOSED;
    public boolean gameplay() {
        return this == INFILTRATION || this == VAULT || this == EXTRACTION;
    }
}
