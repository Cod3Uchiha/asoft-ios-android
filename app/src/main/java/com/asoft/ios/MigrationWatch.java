package com.asoft.ios;

import java.util.Locale;

/**
 * Decides when the iOS 26 second boot's DataMigration wait (PhoneSetup26.iosWallpaper) has gone wrong, from what the
 * app sees without the guest's help: whether its shell answered, the new console text, the frames shown. See
 * notes/handover-2026-11-13/k18-stall.md for the stall: the shell stops answering, launchd respawns bash every
 * ~10 s, the pid counter climbs past 25,000 (a healthy boot ends near 1,250). A shell that answers is never
 * stalled, however slow; the limits count from its last answer (SetupLimits). A sign of life is a console line not
 * printed before, a higher pid, a new frame, an answer. No Android here.
 */
final class MigrationWatch {
    enum Verdict { WAIT, STALL_CHURN, STALL_DEAD, STALL_SILENT, CEILING }

    private final long start;
    private final ConsoleLife life = new ConsoleLife();
    private long lastAnswer, lastSign;
    private int pidAtAnswer, pidSeen, frames = -1;

    MigrationWatch(long nowMs) {
        start = nowMs;
        lastAnswer = nowMs;
        lastSign = nowMs;
    }

    /**
     * One look at the guest: @answered the shell answered this pass; @newText the console text since the last
     * look; @framesShown the screen's frame counter (-1 when unknown).
     */
    void observe(long nowMs, boolean answered, String newText, int framesShown) {
        int novel = life.feed(newText);
        // a pid higher than any before is a program launchd has just started: the guest runs, even when every line
        // it prints is one it has printed before (the S25's churn of 2026-11-14 printed its last new line 13 min
        // in and came back after 26). Such a guest is ended by the churn limit, not as one that shows nothing.
        if (novel > 0 || life.maxPid() > pidSeen) {
            lastSign = nowMs;
        }
        pidSeen = life.maxPid();
        if (framesShown >= 0) {
            if (frames >= 0 && framesShown > frames) {
                lastSign = nowMs;
            }
            frames = framesShown;
        }
        if (answered) {
            lastAnswer = nowMs;
            lastSign = nowMs;
            pidAtAnswer = life.maxPid();
        }
    }

    boolean racing() {
        return life.maxPid() >= SetupLimits.CHURN_PID && life.maxPid() - pidAtAnswer >= SetupLimits.CHURN_RISE;
    }

    Verdict verdict(long nowMs) {
        long silent = nowMs - lastAnswer;
        if (silent >= SetupLimits.MIGRATION_CHURN_SILENT_MS && racing()) {
            return Verdict.STALL_CHURN;
        }
        if (nowMs - lastSign >= SetupLimits.MIGRATION_DEAD_SILENT_MS && silent >= SetupLimits.MIGRATION_DEAD_SILENT_MS) {
            return Verdict.STALL_DEAD;
        }
        if (silent >= SetupLimits.MIGRATION_SILENT_MS) {
            return Verdict.STALL_SILENT;
        }
        if (nowMs - start >= SetupLimits.MIGRATION_CEILING_MS) {
            return Verdict.CEILING;
        }
        return Verdict.WAIT;
    }

    long silentMin(long nowMs) {
        return (nowMs - lastAnswer) / SetupLimits.MIN;
    }

    int maxPid() {
        return life.maxPid();
    }

    /** What the setup screen adds while the shell is quiet; "" while it answers. */
    String quietStatus(long nowMs) {
        long silent = silentMin(nowMs);
        if (silent < 1) {
            return "";
        }
        long sign = (nowMs - lastSign) / SetupLimits.MIN;
        return String.format(Locale.ROOT, " iOS has not answered for %d min; last sign of life %s.", silent,
                sign < 1 ? "just now" : sign + " min ago");
    }

    /** What went wrong, in plain words, for the log and the error the user reads. */
    static String stallText(Verdict v, long silentMin) {
        switch (v) {
            case STALL_CHURN:
                return "iOS stopped responding during its own setup: its programs have been restarting over and over for " + silentMin + " min.";
            case STALL_DEAD:
                return "iOS stopped responding during its own setup: nothing has changed on it for " + silentMin + " min.";
            case STALL_SILENT:
                return "iOS stopped responding during its own setup " + silentMin + " min ago.";
            default:
                return "";
        }
    }
}
