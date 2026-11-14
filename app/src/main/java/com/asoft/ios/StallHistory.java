package com.asoft.ios;

import java.util.Map;
import java.util.TreeMap;

/**
 * How many times in a row a setup step stopped for the same reason, kept as plain text in a file of the setup's
 * folder ("wallpaper:churn=2" lines). The step's success clears it. No Android here.
 */
final class StallHistory {
    private final Map<String, Integer> counts = new TreeMap<>();

    static StallHistory parse(String text) {
        StallHistory h = new StallHistory();
        if (text == null) {
            return h;
        }
        for (String line : text.split("\n")) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                h.counts.put(line.substring(0, eq).trim(), Integer.parseInt(line.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
                // a damaged line is a lost count, nothing more
            }
        }
        return h;
    }

    static StallHistory load(java.io.File f) {
        try {
            return parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return new StallHistory();
        }
    }

    void save(java.io.File f) {
        try {
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
            // the count is a help for the next report: losing it only delays "a report helps"
        }
    }

    /** One more stall of @kind (e.g. "churn"); a stall of another kind of the same step ends that run of them. */
    int record(String step, String kind) {
        String key = step + ":" + kind;
        int n = counts.getOrDefault(key, 0) + 1;
        counts.keySet().removeIf(k -> k.startsWith(step + ":"));
        counts.put(key, n);
        return n;
    }

    void clear(String step) {
        counts.keySet().removeIf(k -> k.startsWith(step + ":"));
    }

    int count(String step, String kind) {
        return counts.getOrDefault(step + ":" + kind, 0);
    }

    String text() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return b.toString();
    }

    /** What the user reads after stall number @n. */
    static String userText(String what, int n) {
        if (n >= SetupLimits.STALLS_BEFORE_REPORT) {
            return what + " This is the " + ordinal(n) + " time in a row. This phone hits a known problem, and a report helps: open Logs,"
                    + " share the log as a file and send it to the developer. Resume tries again.";
        }
        return what + " Tap Resume: iOS starts this boot again. The log keeps what it printed.";
    }

    private static String ordinal(int n) {
        return n == 3 ? "third" : n == 4 ? "fourth" : n + "th";
    }
}
