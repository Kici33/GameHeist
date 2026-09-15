package dev.gameheist.paper.command;

import dev.gameheist.domain.match.MatchResult;
import dev.gameheist.paper.gameplay.RunTimeFormat;
import java.util.*;

public final class ResultHistory {
    private ResultHistory() { }
    public record Page(int number, int pages, int total, List<MatchResult> entries) {
        public Page { entries = List.copyOf(entries); }
    }
    public static Page page(List<MatchResult> results, int number) {
        int pages = Math.max(1, (results.size() + 9) / 10);
        if (number < 1 || number > pages) throw new IllegalArgumentException("Results page must be between 1 and " + pages);
        var ordered = results.stream().sorted(Comparator.comparing(MatchResult::finishedAt).reversed()
                .thenComparing(MatchResult::matchId)).toList();
        int start = (number - 1) * 10;
        return new Page(number, pages, results.size(), ordered.subList(start, Math.min(start + 10, ordered.size())));
    }
    public static String describe(MatchResult result) {
        String time = result.gameplayMillis().isPresent() ? RunTimeFormat.format(result.gameplayMillis().getAsLong()) : "unavailable";
        return result.matchId() + " " + result.outcome() + " " + result.reason() + " | " + result.arena()
                + " " + result.difficulty() + " crew=" + result.participants().size() + " " + result.alarm()
                + " bags=" + result.securedBags() + " time=" + time;
    }
}
