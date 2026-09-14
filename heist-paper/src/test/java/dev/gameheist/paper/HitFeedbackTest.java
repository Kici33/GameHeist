package dev.gameheist.paper;

import dev.gameheist.paper.gameplay.HitFeedback;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HitFeedbackTest {
    @Test void hitPersistsThroughHudRefreshesAndExpiresWithoutFurtherShots() {
        var feedback = new HitFeedback();
        assertEquals("", feedback.text(0));
        feedback.hit(10, "guard", 40);
        assertEquals("Hit guard · 40 HP", feedback.text(15));
        assertEquals("Hit guard · 40 HP", feedback.text(29));
        assertEquals("", feedback.text(30));
    }
    @Test void latestDamageReplacesOldTargetAndDefeatHasDistinctFeedback() {
        var feedback = new HitFeedback();
        feedback.hit(0, "first", 20);
        feedback.hit(6, "second", 0);
        assertEquals("Defeated second", feedback.text(20));
        assertEquals("", feedback.text(26));
        assertEquals("", new HitFeedback().text(20));
    }
}
