package dev.gameheist.paper.pack;

import java.util.ArrayList;
import java.util.List;

/** One timeline per match; drill samples last less than the twenty-tick repeat interval. */
public final class AudioTimeline {
    private boolean alarm, jammed, complete, working;
    private long nextWork;
    public List<HeistAudio.Cue> update(long tick, boolean active, boolean loud, boolean started, boolean jam, boolean done) {
        var cues = new ArrayList<HeistAudio.Cue>();
        if (active) {
            if (loud && !alarm) cues.add(HeistAudio.Cue.ALARM);
            if (jam && !jammed) cues.add(HeistAudio.Cue.DRILL_JAM);
            if (done && !complete) cues.add(HeistAudio.Cue.DRILL_COMPLETE);
            boolean running = started && !jam && !done;
            if (running && (!working || tick >= nextWork)) {
                cues.add(HeistAudio.Cue.DRILL_WORK);
                nextWork = tick + 20;
            }
            working = running;
        } else working = false;
        alarm = loud;
        jammed = jam;
        complete = done;
        return List.copyOf(cues);
    }
}
