package dev.gameheist.paper;

import dev.gameheist.paper.gameplay.RunTimeFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunTimeFormatTest {
    @Test void preservesMillisecondsAcrossMinuteBoundaries() {
        assertEquals("0:00.000", RunTimeFormat.format(0));
        assertEquals("0:59.999", RunTimeFormat.format(59999));
        assertEquals("1:00.000", RunTimeFormat.format(60000));
        assertEquals("1:05.432", RunTimeFormat.format(65432));
        assertEquals("60:00.001", RunTimeFormat.format(3600001));
        assertNotEquals(RunTimeFormat.format(65431), RunTimeFormat.format(65432));
        assertThrows(IllegalArgumentException.class, () -> RunTimeFormat.format(-1));
    }
}
