package dev.gameheist.domain.match;

/** Immutable reconnect state exposed to adapters. Remaining times are based on the match clock. */
public record ParticipantConnection(ConnectionStatus status, long vulnerableMillis, long graceMillis, long generation) {
    public ParticipantConnection {
        if (vulnerableMillis < 0 || graceMillis < 0 || generation < 0) throw new IllegalArgumentException("Invalid connection state");
    }
    public boolean connected() { return status == ConnectionStatus.CONNECTED; }
    public boolean reconnectable() { return status == ConnectionStatus.VULNERABLE || status == ConnectionStatus.RESERVED; }
}
