package dev.gameheist.paper.gameplay;

/** Match-owned feedback using the same server ticks as the HUD; no extra scheduled task. */
public final class HitFeedback {
    private String message = "";
    private long expires;
    public void hit(long tick, String guard, int remainingHealth) {
        if (remainingHealth < 0) throw new IllegalArgumentException("Negative guard health");
        message = remainingHealth == 0 ? "Defeated " + guard : "Hit " + guard + " · " + remainingHealth + " HP";
        expires = tick + 20;
    }
    public String text(long tick) { return tick < expires ? message : ""; }
}
