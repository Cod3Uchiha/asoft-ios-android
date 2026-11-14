package com.asoft.ios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The text of the "works" report: the issue's title, its body and the body cut to fit the issue link. Pure
 * (WorksTextTest). Nothing in it tells one phone from another of its kind: no serial, no Android ID, no account,
 * no address, no path.
 */
final class WorksText {
    private WorksText() {}

    /** The title's prefix: GitHub drops labels= from a prefilled link for people without triage rights, so this marks the issues. */
    static final String TITLE_PREFIX = "Works: ";
    static final String INTRO = "This comes from the app's \"works\" report: a guest ran on this phone for a while and the phone"
            + " is not on the list of tested phones yet.";
    /** The last line of the body, left empty for the user to fill in. */
    static final String CLOSING = "Anything that does not work: ";
    /** A fact line longer than this is cut (a setup with many steps). */
    private static final int MAX_LINE = 300;

    static String guestName(String guest) {
        return TestedPhones.WATCH.equals(guest) ? "watchOS" : TestedPhones.IOS16.equals(guest) ? "iOS 16" : "iOS 26";
    }

    /** "Works: Samsung SM-S938B (SM8750, Adreno (TM) 830): iOS 26" */
    static String title(String phone, String soc, String gpu, String guest) {
        return TITLE_PREFIX + phone + " (" + soc + (gpu.isEmpty() ? "" : ", " + gpu) + "): " + guestName(guest);
    }

    /** What the report is made of; "" or -1 where it is not known. */
    static final class In {
        String guest = "", app = "", android = "", phoneRam = "", soc = "", gpu = "", driver = "", counter = "";
        WorksRun run;
        /** the last finished setup of this guest's group on this phone, null = none */
        SetupTimes.Kept setup;
    }

    /** "45 s", "12 min", "1 h 5 min": whole numbers, as the log writes durations. */
    static String dur(int s) {
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return s / 60 + " min" + (s % 60 == 0 ? "" : " " + s % 60 + " s");
        }
        return s / 3600 + " h" + (s % 3600 / 60 == 0 ? "" : " " + s % 3600 / 60 + " min");
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.0f%%", v);
    }

    private static final String[] THERMAL = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};

    /** The fact lines, most important first: a cut takes the last ones. */
    static List<String> facts(In in) {
        List<String> f = new ArrayList<>();
        WorksRun r = in.run;
        f.add("App: " + in.app);
        f.add("Android: " + in.android);
        f.add("Phone RAM: " + in.phoneRam);
        f.add("SoC: " + in.soc);
        f.add("GPU: " + (in.gpu.isEmpty() ? "not used by this guest" : in.gpu + (in.driver.isEmpty() ? "" : ", driver " + in.driver)));
        f.add("Counter: " + in.counter);
        f.add("Guest: " + guestName(in.guest) + (r.ramMiB > 0 ? ", " + r.ramMiB + " MiB RAM" : "")
                + (r.screen.isEmpty() ? "" : ", screen " + r.screen));
        if (in.setup != null) {
            StringBuilder s = new StringBuilder("Setup: ").append(dur(in.setup.totalS)).append(" in total");
            String sep = " (";
            for (Map.Entry<String, Integer> e : in.setup.stepsS.entrySet()) {
                s.append(sep).append(e.getKey()).append(" ").append(dur(e.getValue()));
                sep = ", ";
            }
            f.add(s.append(in.setup.stepsS.isEmpty() ? "" : ")").toString());
        }
        if (r.coldS >= 0) {
            f.add("Cold boot: first frame of the live view " + dur(r.coldS) + " after QEMU started");
        }
        if (r.loadS >= 0) {
            f.add("Quick-save load: first frame of the live view " + dur(r.loadS) + " after QEMU started");
        }
        if (r.peakFps >= 1) {
            f.add("Live view: up to " + r.peakFps + " fps (the busiest second of the run)");
        } else if (r.fps < 0) {
            f.add("Live view: not measured (the screen was not open)");
        }
        f.add("vCPUs: " + (r.halted < 0 ? "no sample" : "halted " + pct(r.halted)
                + (r.el1 < 0 ? "" : ", el1 " + pct(r.el1) + ", el0-tcg " + pct(r.el0tcg) + ", el0-native " + pct(r.el0nat))));
        f.add("Thermal: " + (r.thermalMax < 0 ? "unknown"
                : r.thermalMax + (r.thermalMax < THERMAL.length ? " (" + THERMAL[r.thermalMax] + ")" : "") + " at the highest during the run"));
        f.add("Ran: " + dur(r.aliveS) + " when this was written");
        if (r.lowestAvailMb >= 0) {
            f.add("MemAvailable: " + r.lowestAvailMb + " MB at the lowest");
        }
        for (int i = 0; i < f.size(); i++) {
            if (f.get(i).length() > MAX_LINE) {
                f.set(i, f.get(i).substring(0, MAX_LINE - 1) + "…");
            }
        }
        return f;
    }

    /** The body: the intro, a blank line, @facts one per line, a blank line, the closing line. */
    static String body(List<String> facts) {
        StringBuilder b = new StringBuilder(INTRO).append("\n\n");
        for (String l : facts) {
            b.append(l).append('\n');
        }
        return b.append('\n').append(CLOSING).toString();
    }

    /**
     * The body whose issue link (CrashText.issueUrl) stays within @maxUrl (CrashReport.MAX_URL): the last facts go
     * first, so the first facts (app, Android, SoC, GPU) are the last to go; the intro and the closing line stay.
     * The title is the caller's and is never cut.
     */
    static String fit(String repo, String title, List<String> facts, int maxUrl) {
        List<String> kept = new ArrayList<>(facts);
        while (!kept.isEmpty() && CrashText.issueUrl(repo, title, body(kept)).length() > maxUrl) {
            kept.remove(kept.size() - 1);
        }
        return body(kept);
    }
}
