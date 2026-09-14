package dev.gameheist.paper;

import dev.gameheist.paper.pack.AudioTimeline;
import dev.gameheist.paper.pack.HeistAudio.Cue;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AudioTimelineTest {
    @Test void alarmAndDrillEdgesPlayOnceWhileMotorRepeatsAtBoundedRate() {
        var timeline = new AudioTimeline();
        assertEquals(List.of(Cue.ALARM, Cue.DRILL_WORK), timeline.update(1, true, true, true, false, false));
        for (int tick = 2; tick < 21; tick++) assertTrue(timeline.update(tick, true, true, true, false, false).isEmpty());
        assertEquals(List.of(Cue.DRILL_WORK), timeline.update(21, true, true, true, false, false));
        assertEquals(List.of(Cue.DRILL_JAM), timeline.update(22, true, true, true, true, false));
        assertTrue(timeline.update(50, true, true, true, true, false).isEmpty());
        assertEquals(List.of(Cue.DRILL_WORK), timeline.update(51, true, true, true, false, false));
        assertEquals(List.of(Cue.DRILL_COMPLETE), timeline.update(52, true, true, true, false, true));
        assertTrue(timeline.update(90, true, true, true, false, true).isEmpty());
    }
    @Test void inactiveMatchesAreSilentAndTimelinesAreIsolated() {
        var first = new AudioTimeline();
        first.update(1, true, true, true, false, false);
        assertTrue(first.update(99, false, true, true, false, false).isEmpty());
        assertEquals(List.of(Cue.ALARM), new AudioTimeline().update(99, true, true, false, false, false));
    }
}
