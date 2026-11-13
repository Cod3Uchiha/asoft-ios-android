package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * How far a system's setup is, in the words the home screen and the setup page share: "step 3 of 12, 42%" while it
 * runs, "Set up, 6.4 GB, 12 Nov" when it is there. The wording is plain functions (SetupStateTest); what feeds it
 * (the step lists, the marks of the steps done, the service's current step) is read from PhoneSetup, PhoneSetup26 and
 * SetupService.
 */
final class SetupState {
    private SetupState() {}

    /** The steps of the setup of @os that is running now: keys in order, with a done test; null when none runs for it. */
    static final class Plan {
        final List<String> keys = new ArrayList<>();
        Predicate<String> done = k -> false;
        /** The key of the step that runs now ("" between steps). */
        String current = "";
        /** The system whose steps come first and are not @os's own ("watchOS", when iOS 26 waits for it), or null. */
        String waitsFor;
    }

    /**
     * 1-based place of the step that runs: that of @current when it is in @keys, else the first one not done (all done: the
     * last). 0 for an empty list.
     */
    static int place(List<String> keys, Predicate<String> done, String current) {
        if (keys.isEmpty()) {
            return 0;
        }
        int at = keys.indexOf(current);
        if (at >= 0) {
            return at + 1;
        }
        for (int i = 0; i < keys.size(); i++) {
            if (!done.test(keys.get(i))) {
                return i + 1;
            }
        }
        return keys.size();
    }

    /** "step 3 of 12, 42%": the percent only when there is one (@pct below 0: none). */
    static String progress(int n, int m, int pct) {
        return "step " + n + " of " + m + (pct >= 0 ? ", " + pct + "%" : "");
    }

    /** What a running setup of one system says, for @plan, or null when no step list is known. */
    static String progress(Plan plan, int pct) {
        if (plan == null || plan.keys.isEmpty()) {
            return null;
        }
        if (plan.waitsFor != null) {
            return "waits for " + plan.waitsFor + " (" + progress(place(plan.keys, plan.done, ""), plan.keys.size(), -1) + ")";
        }
        return progress(place(plan.keys, plan.done, plan.current), plan.keys.size(), pct);
    }

    /** "12.3 GB", "450 MB". */
    static String size(long bytes) {
        return bytes >= 1e9 ? String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
                : String.format(Locale.ROOT, "%.0f MB", Math.max(1, bytes / 1e6));
    }

    /** The state line of a system on the setup page; @progress is progress(), @size and @date may be empty. */
    static String line(boolean installed, boolean running, boolean paused, String progress, String size, String date) {
        if (running) {
            String p = progress == null || progress.isEmpty() ? "" : ": " + progress;
            return (paused ? "Paused" : "Setting up") + p;
        }
        if (installed) {
            StringBuilder b = new StringBuilder("Set up");
            if (size != null && !size.isEmpty()) {
                b.append(", ").append(size);
            }
            if (date != null && !date.isEmpty()) {
                b.append(", ").append(date);
            }
            return b.toString();
        }
        return progress != null && !progress.isEmpty() ? "Stopped at " + progress : "Not set up";
    }

    /** "12 Nov" of a file's time, "" without one. */
    static String day(long millis) {
        if (millis <= 0) {
            return "";
        }
        java.time.LocalDate d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
    }

    // -- what feeds it

    /** The plan of iOS 16's setup, or null when the service does not run it. */
    static Plan plan16(Context c) {
        SetupService.State s = SetupService.state;
        if (!s.running || "a26".equals(s.target)) {
            return null;
        }
        Plan p = new Plan();
        for (String[] st : SetupScreen.STEPS) {
            p.keys.add(st[0]);
        }
        // (the download's marker goes when its files are dropped early: a built disk means it was done)
        p.done = k -> PhoneSetup.done(c, k) || "ipsw".equals(k) && PhoneSetup.done(c, "disk");
        p.current = s.step;
        return p;
    }

    /** The plan of @group (PhoneSetup26.IOS26 or WATCH) inside the running iOS 26 / watchOS setup, or null when it is not in it. */
    static Plan plan26(Context c, String group) {
        SetupService.State s = SetupService.state;
        if (!s.running || !"a26".equals(s.target) || !PhoneSetup26.groups(c).contains(group)) {
            return null;
        }
        Plan p = new Plan();
        for (String g : new String[] {PhoneSetup26.SHARED, group}) {
            for (String[] st : PhoneSetup26.steps(g)) {
                p.keys.add(g + ":" + st[0]);
            }
        }
        p.done = k -> {
            String[] a = k.split(":");
            return PhoneSetup26.done(c, a[0], a[1]);
        };
        p.current = s.step;
        // the other system's steps run first (watchOS before iOS 26) while this one's own have not begun
        String other = PhoneSetup26.IOS26.equals(group) ? PhoneSetup26.WATCH : PhoneSetup26.IOS26;
        if (s.step.startsWith(other + ":")) {
            p.waitsFor = PhoneSetup26.WATCH.equals(other) ? "watchOS" : "iOS 26";
        }
        return p;
    }

    /** The home screen's and the setup page's progress text of @os while its setup runs: "step 3 of 12, 42%", else null. */
    static String running(Context c, int os) {
        Plan p = os == Guest.IOS16 ? plan16(c) : plan26(c, os == Guest.WATCH ? PhoneSetup26.WATCH : PhoneSetup26.IOS26);
        return progress(p, SetupService.state.pct);
    }

    /** @os is the system the running setup is making (a paused one counts). */
    static boolean settingUp(Context c, int os) {
        return SetupService.state.running && (os == Guest.IOS16 ? plan16(c) != null
                : plan26(c, os == Guest.WATCH ? PhoneSetup26.WATCH : PhoneSetup26.IOS26) != null);
    }

    /** When @os was set up: its main disk's time (a file the setup or an import wrote last), 0 when unknown. */
    static long installedAt(Context c, int os) {
        File f = os == Guest.IOS16 ? new File(Installer.dir(c), "disk.qcow2")
                : os == Guest.WATCH ? new File(c.getFilesDir(), "watchos/" + WatchProfile.BASE)
                : new File(c.getFilesDir(), "ios26/" + Guest.AOD26_BASE);
        return f.lastModified();
    }
}
