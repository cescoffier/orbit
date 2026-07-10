package io.quarkus.orbit.pulse.release;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReleaseNotesParser {

    private static final Pattern PR_LINE = Pattern.compile(
            "^\\s*\\*\\s+\\[#(\\d+)]\\(https://github\\.com/[^/]+/[^/]+/(?:pull|issues)/\\d+\\)\\s+-\\s+(.+)$",
            Pattern.MULTILINE
    );

    private ReleaseNotesParser() {}

    public static List<Integer> parsePrNumbers(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<Integer> numbers = new ArrayList<>();
        Matcher m = PR_LINE.matcher(body);
        while (m.find()) {
            numbers.add(Integer.parseInt(m.group(1)));
        }
        return numbers;
    }
}
