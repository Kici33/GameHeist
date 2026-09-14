package dev.gameheist.paper;

import dev.gameheist.paper.gameplay.CrewStatus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrewStatusTest {
    @Test void healthBarTracksAvailableTeammatesAndRescuePriority() {
        UUID viewer = UUID.randomUUID(), other = UUID.randomUUID();
        var members = List.of(new CrewStatus.Member(viewer, "Self", 0, true),
                new CrewStatus.Member(other, "Other", 50, true));
        assertEquals(.5f, CrewStatus.healthFraction(viewer, members));
        assertFalse(CrewStatus.needsRescue(viewer, members));
        var downed = List.of(new CrewStatus.Member(other, "Other", 0, true));
        assertTrue(CrewStatus.needsRescue(viewer, downed));
        assertEquals(0f, CrewStatus.healthFraction(viewer, downed));
        var away = List.of(new CrewStatus.Member(other, "Other", 100, false));
        assertEquals(0f, CrewStatus.healthFraction(viewer, away));
        assertFalse(CrewStatus.needsRescue(viewer, away));
        assertEquals(0f, CrewStatus.healthFraction(viewer, List.of()));
    }
    @Test void downedTeammatesTakePriorityAndViewerIsExcluded() {
        UUID viewer = UUID.randomUUID();
        var members = List.of(new CrewStatus.Member(viewer, "Self", 50, true),
                new CrewStatus.Member(UUID.randomUUID(), "Alice", 70, true),
                new CrewStatus.Member(UUID.randomUUID(), "Zoe", 0, true));
        assertEquals("Crew — Zoe: DOWN | Alice: 70 HP", CrewStatus.format(viewer, members));
        var reversed = new ArrayList<>(members);
        Collections.reverse(reversed);
        assertEquals(CrewStatus.format(viewer, members), CrewStatus.format(viewer, reversed));
    }
    @Test void absentPlayersDoNotLookRescuableAndSoloHasNoCrewSummary() {
        UUID viewer = UUID.randomUUID(), other = UUID.randomUUID();
        assertEquals("Crew — Zoe: AWAY", CrewStatus.format(viewer,
                List.of(new CrewStatus.Member(other, "Zoe", 0, false))));
        assertEquals("", CrewStatus.format(viewer, List.of(new CrewStatus.Member(viewer, "Self", 100, true))));
        assertEquals("Crew — Zoe: 50 HP", CrewStatus.format(viewer,
                List.of(new CrewStatus.Member(other, "Zoe", 50, true))));
    }
}
