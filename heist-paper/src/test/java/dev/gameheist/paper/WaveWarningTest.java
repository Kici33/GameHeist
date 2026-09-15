package dev.gameheist.paper;

import dev.gameheist.paper.gameplay.WaveWarning;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaveWarningTest {
    @Test void warnsOnceAtThresholdWithoutRepeatingAcrossHudTicks() {
        var warning = new WaveWarning();
        assertTrue(warning.poll(true, OptionalLong.empty()).isEmpty());
        assertTrue(warning.poll(true, OptionalLong.of(5001)).isEmpty());
        assertEquals(5, warning.poll(true, OptionalLong.of(5000)).orElseThrow());
        assertTrue(warning.poll(true, OptionalLong.of(4000)).isEmpty());
        assertTrue(warning.poll(true, OptionalLong.empty()).isEmpty());
    }
    @Test void delayedFramesUseActualRemainingTimeAndExpiredOrInactiveRunsStayQuiet() {
        var warning = new WaveWarning();
        assertTrue(warning.poll(false, OptionalLong.of(3000)).isEmpty());
        assertEquals(3, warning.poll(true, OptionalLong.of(2001)).orElseThrow());
        assertTrue(new WaveWarning().poll(true, OptionalLong.of(0)).isEmpty());
        assertTrue(new WaveWarning().poll(true, OptionalLong.of(-1)).isEmpty());
        assertEquals(1, new WaveWarning().poll(true, OptionalLong.of(1)).orElseThrow());
    }
}
