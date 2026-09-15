package dev.gameheist.paper.gameplay;

import java.util.Locale;

/** Preserve the recorded milliseconds so distinct records never look identical after rounding. */
public final class RunTimeFormat {
    private RunTimeFormat() { }
    public static String format(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Negative run duration");
        return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60_000, millis / 1000 % 60, millis % 1000);
    }
}
