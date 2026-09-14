package dev.gameheist.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public final class Checks {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private Checks() {}
    public static String id(String value) {
        if (!ID.matcher(Objects.requireNonNull(value, "id")).matches()) {
            throw new IllegalArgumentException("Invalid identifier: " + value);
        }
        return value;
    }
}
