package dev.gameheist.runtime.npc;

import dev.gameheist.domain.npc.GuardDecision;

public record GuardDiagnostic(String id, GuardDecision decision, long updates, int pathFailures, long updateMicros) {}
