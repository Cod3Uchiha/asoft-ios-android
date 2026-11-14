package com.asoft.ios;

import java.util.Map;

/**
 * What the app kept of a guest's run that got somewhere (WorksReport.store): the facts of the "works" report, and
 * what decides whether the run counts (good). One file per guest, written by RunStats while the run lives, marked
 * crashed by CrashReport when a record of a crash is written. Pure (WorksRunTest).
 */
final class WorksRun {
    /** How long QEMU has to stay alive after the live view's first frame for the run to count: 10 minutes. */
    static final int GOOD_ALIVE_S = 600;

    /** "ios26", "ios16" or "watch" */
    String guest = "";
    /** the app version it ran on (versionName) */
    String app = "";
    /** this run was a quick-save load, not a cold boot */
    boolean resumed;
    /** seconds from QEMU's start to the live view's first frame; -1 = never seen */
    int firstFrameS = -1;
    /** seconds QEMU had been alive at the last write */
    int aliveS;
    /** the run ended in a crash record */
    boolean crashed;
    /** the first-frame time of the newest cold boot / quick-save load on this app version; -1 = none */
    int coldS = -1, loadS = -1;
    /** the live view's newest fps (-1 = it was never drawing); the EL sampler's newest shares (-1 = none) */
    double fps = -1, halted = -1, el1 = -1, el0tcg = -1, el0nat = -1;
    /** guest RAM in MiB (0 = unknown) and the screen ("" = none or fixed) */
    int ramMiB;
    String screen = "";
    /** the highest thermal status seen during the run (PowerManager's 0..6; -1 = unknown) and the lowest MemAvailable (MB; -1) */
    int thermalMax = -1;
    long lowestAvailMb = -1;

    /** The run counts: on this app version, a first frame, alive long enough, no crash record. */
    boolean good(String appVersion) {
        return app.equals(appVersion) && firstFrameS >= 0 && aliveS >= GOOD_ALIVE_S && !crashed;
    }

    String toJson() {
        StringBuilder b = new StringBuilder("{");
        b.append("\"guest\":").append(MiniJson.quote(guest));
        b.append(",\"app\":").append(MiniJson.quote(app));
        b.append(",\"resumed\":").append(resumed);
        b.append(",\"firstFrameS\":").append(firstFrameS);
        b.append(",\"aliveS\":").append(aliveS);
        b.append(",\"crashed\":").append(crashed);
        b.append(",\"coldS\":").append(coldS);
        b.append(",\"loadS\":").append(loadS);
        b.append(",\"fps\":").append(fps);
        b.append(",\"halted\":").append(halted);
        b.append(",\"el1\":").append(el1);
        b.append(",\"el0tcg\":").append(el0tcg);
        b.append(",\"el0nat\":").append(el0nat);
        b.append(",\"ramMiB\":").append(ramMiB);
        b.append(",\"screen\":").append(MiniJson.quote(screen));
        b.append(",\"thermalMax\":").append(thermalMax);
        b.append(",\"lowestAvailMb\":").append(lowestAvailMb);
        return b.append('}').toString();
    }

    /** The run kept in @json; null when it is not one (a damaged file counts as no run). */
    static WorksRun fromJson(String json) {
        Object o = MiniJson.parse(json);
        if (!(o instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) o;
        WorksRun r = new WorksRun();
        r.guest = MiniJson.str(m, "guest");
        r.app = MiniJson.str(m, "app");
        r.resumed = MiniJson.bool(m, "resumed");
        r.firstFrameS = (int) MiniJson.num(m, "firstFrameS", -1);
        r.aliveS = (int) MiniJson.num(m, "aliveS", 0);
        r.crashed = MiniJson.bool(m, "crashed");
        r.coldS = (int) MiniJson.num(m, "coldS", -1);
        r.loadS = (int) MiniJson.num(m, "loadS", -1);
        r.fps = MiniJson.num(m, "fps", -1);
        r.halted = MiniJson.num(m, "halted", -1);
        r.el1 = MiniJson.num(m, "el1", -1);
        r.el0tcg = MiniJson.num(m, "el0tcg", -1);
        r.el0nat = MiniJson.num(m, "el0nat", -1);
        r.ramMiB = (int) MiniJson.num(m, "ramMiB", 0);
        r.screen = MiniJson.str(m, "screen");
        r.thermalMax = (int) MiniJson.num(m, "thermalMax", -1);
        r.lowestAvailMb = (long) MiniJson.num(m, "lowestAvailMb", -1);
        return r.guest.isEmpty() ? null : r;
    }
}
