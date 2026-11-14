package com.asoft.ios;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How long a finished setup run took, in total and per step (SetupService feeds it, WorksReport keeps the last
 * finished run of iOS 16 and of the iOS 26 / watchOS group). Pause and waiting for Wi-Fi count: it is the run's
 * wall time. A run that went on after a stop covers only the part since the resume. Pure (SetupTimesTest).
 */
final class SetupTimes {
    private final long startMs;
    private String cur;
    private long curSince;
    private long endMs = -1;
    private final LinkedHashMap<String, Long> ms = new LinkedHashMap<>();

    SetupTimes(long startMs) {
        this.startMs = startMs;
        this.curSince = startMs;
    }

    private void close(long nowMs) {
        if (cur != null) {
            ms.merge(cur, Math.max(0, nowMs - curSince), Long::sum);
        }
    }

    /** The run is at the step called @label from @nowMs on (the first step counts from the run's start). */
    void step(String label, long nowMs) {
        if (label.equals(cur)) {
            return;
        }
        boolean first = cur == null && ms.isEmpty();
        close(nowMs);
        cur = label;
        curSince = first ? startMs : nowMs;
    }

    /** The run is done at @nowMs. */
    void finish(long nowMs) {
        close(nowMs);
        cur = null;
        endMs = nowMs;
    }

    int totalS() {
        return endMs < 0 ? -1 : (int) Math.max(0, (endMs - startMs) / 1000);
    }

    /** Seconds per step label, in the order the steps came. */
    LinkedHashMap<String, Integer> stepsS() {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : ms.entrySet()) {
            out.put(e.getKey(), (int) (e.getValue() / 1000));
        }
        return out;
    }

    String toJson() {
        StringBuilder b = new StringBuilder("{\"totalS\":").append(totalS()).append(",\"steps\":[");
        boolean first = true;
        for (Map.Entry<String, Integer> e : stepsS().entrySet()) {
            b.append(first ? "" : ",").append("[").append(MiniJson.quote(e.getKey())).append(",").append(e.getValue()).append("]");
            first = false;
        }
        return b.append("]}").toString();
    }

    /** What a finished run kept: total seconds and the steps; null for a file that is no record. */
    static final class Kept {
        int totalS;
        final LinkedHashMap<String, Integer> stepsS = new LinkedHashMap<>();
    }

    static Kept fromJson(String json) {
        Object o = MiniJson.parse(json);
        if (!(o instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) o;
        Kept k = new Kept();
        k.totalS = (int) MiniJson.num(m, "totalS", -1);
        if (k.totalS < 0) {
            return null;
        }
        if (m.get("steps") instanceof List) {
            for (Object s : (List<?>) m.get("steps")) {
                if (s instanceof List && ((List<?>) s).size() == 2 && ((List<?>) s).get(0) instanceof String
                        && ((List<?>) s).get(1) instanceof Double) {
                    k.stepsS.put((String) ((List<?>) s).get(0), (int) (double) (Double) ((List<?>) s).get(1));
                }
            }
        }
        return k;
    }
}
