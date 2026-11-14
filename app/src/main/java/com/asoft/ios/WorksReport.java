package com.asoft.ios;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * The "works" report: when a guest has run on this phone for a while and the phone's chip is not on the project's
 * list of tested phones (devices.json, TestedPhones), home offers one quiet line, and a dialog that shows what a
 * prefilled GitHub issue would say. The decision is WorksOffer, the text WorksText; this class keeps the files
 * and preferences and holds what needs Android.
 *
 * Kept: files/devices.json (the last fetched list) and files/works/GUEST.json (the guest's good run, WorksRun)
 * with the preferences file "works": listAt (when the list was fetched), dismissed.GUEST / opened.GUEST (the app
 * version the user said "Not now" or opened the report on), setup.ios16 / setup.a26 (SetupTimes of the last finished
 * setup). The list is fetched with the update check (UpdateCheck.check), at most once a day, and only while the
 * update check is on. Nothing is sent by the app: the issue is the user's to read and submit.
 */
final class WorksReport {
    private WorksReport() {}

    private static final String PREFS = "works";
    private static final long FETCH_EVERY_MS = 24 * 3600_000L;
    /** A debug build's stand-in for the fetched list (files/works-test-devices.json), for trying the offer without the repository. */
    private static final String TEST_LIST = "works-test-devices.json";
    private static final int MAX_LIST = 1 << 20;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String guestKey(int os) {
        return os == Guest.WATCH ? TestedPhones.WATCH : os == Guest.IOS26 ? TestedPhones.IOS26 : TestedPhones.IOS16;
    }

    /** The app's version name; a good run belongs to it. */
    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** The phone's SoC: Build.SOC_MODEL (Android 12+), else Build.HARDWARE; "" when neither says. */
    static String soc() {
        String s = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "";
        if (s == null || s.trim().isEmpty() || s.equalsIgnoreCase("unknown")) {
            s = Build.HARDWARE;
        }
        return s == null || s.equalsIgnoreCase("unknown") ? "" : s.trim();
    }

    // -- the list

    private static File listFile(Context c) {
        return new File(c.getFilesDir(), "devices.json");
    }

    /**
     * Fetches the list when the cached one is a day old, on the update check's thread (@version: its User-Agent).
     * A fetched file that is no list removes the cache: no list, no offer.
     */
    static void fetchList(Context c, String version) {
        long now = System.currentTimeMillis(), at = prefs(c).getLong("listAt", 0);
        String test = UpdateCheck.testFile(c, TEST_LIST);
        if (test == null && at > 0 && now >= at && now - at < FETCH_EVERY_MS) {
            return;
        }
        try {
            String json = test != null ? test : UpdateCheck.get(TestedPhones.URL, "text/plain", version, MAX_LIST);
            if (TestedPhones.parse(json) == null) {
                listFile(c).delete();
                prefs(c).edit().remove("listAt").apply();
                changed();
                AppLog.note(c, "tested phones: the list is not a list, none kept");
                return;
            }
            Files.write(listFile(c).toPath(), json.getBytes(StandardCharsets.UTF_8));
            prefs(c).edit().putLong("listAt", now).apply();
            changed();
            AppLog.note(c, "tested phones: list fetched (" + TestedPhones.parse(json).size() + " rows)");
        } catch (IOException | RuntimeException e) {
            AppLog.note(c, "tested phones: list not fetched: " + e);
        }
    }

    private static List<TestedPhones.Entry> list(Context c) {
        try {
            return TestedPhones.parse(new String(Files.readAllBytes(listFile(c).toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    // -- the run

    private static File runFile(Context c, String guest) {
        File d = new File(c.getFilesDir(), "works");
        d.mkdirs();
        return new File(d, guest + ".json");
    }

    static synchronized WorksRun run(Context c, String guest) {
        try {
            return WorksRun.fromJson(new String(Files.readAllBytes(runFile(c, guest).toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * RunStats' periodic line of a run that has shown its first frame (@firstFrameS) and has lived @aliveS: keeps the
     * run (@peakFps: the most changed frames the live view showed in one second, 0 = none) once it is a good one (WorksRun.GOOD_ALIVE_S) and updates it after that. The first-frame time of the
     * other kind of start (cold boot or load) stays from an earlier run of this app version.
     */
    static synchronized void keep(Context c, int os, boolean resumed, int firstFrameS, int aliveS, double fps, int peakFps, RunStats.El el,
            int ramMiB, String screen, int thermalMax, long lowestAvailMb) {
        if (firstFrameS < 0 || aliveS < WorksRun.GOOD_ALIVE_S) {
            return;
        }
        String guest = guestKey(os), app = version(c);
        WorksRun r = new WorksRun();
        WorksRun old = run(c, guest);
        if (old != null && old.app.equals(app)) {
            r.coldS = old.coldS;
            r.loadS = old.loadS;
        }
        r.guest = guest;
        r.app = app;
        r.resumed = resumed;
        r.firstFrameS = firstFrameS;
        r.aliveS = aliveS;
        if (resumed) {
            r.loadS = firstFrameS;
        } else {
            r.coldS = firstFrameS;
        }
        r.fps = fps;
        r.peakFps = peakFps;
        if (el != null) {
            r.halted = el.halted;
            r.el1 = el.el1;
            r.el0tcg = el.el0tcg;
            r.el0nat = el.el0nat;
        }
        r.ramMiB = ramMiB;
        r.screen = screen;
        r.thermalMax = thermalMax;
        r.lowestAvailMb = lowestAvailMb;
        write(c, guest, r);
        // the later writes only refresh figures; the offer changes when a good run appears
        if (old == null || !old.good(app)) {
            changed();
        }
    }

    private static void write(Context c, String guest, WorksRun r) {
        File f = runFile(c, guest), tmp = new File(f.getPath() + ".tmp");
        try {
            Files.write(tmp.toPath(), r.toJson().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        } catch (IOException e) {
            tmp.delete();
        }
    }

    /** A record of a crash was written for @os' run (CrashReport.capture): its kept run no longer counts. */
    static synchronized void crashed(Context c, int os) {
        String guest = guestKey(os);
        WorksRun r = run(c, guest);
        if (r != null && !r.crashed) {
            r.crashed = true;
            write(c, guest, r);
            changed();
        }
    }

    // -- the setup

    /** A setup run of @a26 (iOS 26 / watchOS) or iOS 16 finished: what it took. */
    static void setupDone(Context c, boolean a26, SetupTimes t) {
        prefs(c).edit().putString(a26 ? "setup.a26" : "setup.ios16", t.toJson()).apply();
    }

    // -- the offer

    /** Which guests the home screen offers the report for now (bit: 1 iOS 26, 2 iOS 16, 4 watchOS); set by refresh. */
    private static volatile int offered;

    static boolean offered(int os) {
        return (offered & (os == Guest.IOS26 ? 1 : os == Guest.IOS16 ? 2 : 4)) != 0;
    }

    /** The offers are worked out at most this often, and at once after changed(). */
    private static final long WORK_OUT_EVERY_MS = 60_000L;
    private static final Object WORK = new Object();
    /** Something that decides the offers changed (the list, a run file, "Not now", "Open on GitHub"). */
    private static volatile boolean dirty = true;
    private static long workedAt;           // SystemClock.elapsedRealtime() of the last working out, 0 = never; under WORK

    /** Something that decides the offers changed: the next refresh works them out again. */
    private static void changed() {
        dirty = true;
    }

    /**
     * The three offers: the list is parsed once, the run files read once, and the GPU check's log (GpuCheck.last
     * rebuilds its whole report) read once, and only when iOS 26 or the watch could be offered at all: a list is
     * there and one of their runs is good.
     */
    private static int workOut(Context c) {
        String app = version(c);
        SharedPreferences p = prefs(c);
        long listAt = p.getLong("listAt", 0), now = System.currentTimeMillis();
        List<TestedPhones.Entry> list = list(c);
        if (list == null) {
            return 0;
        }
        String soc = soc();
        int[] oses = {Guest.IOS26, Guest.IOS16, Guest.WATCH};
        WorksRun[] runs = new WorksRun[3];
        boolean gpuNeeded = false;
        for (int i = 0; i < 3; i++) {
            runs[i] = run(c, guestKey(oses[i]));
            gpuNeeded |= oses[i] != Guest.IOS16 && runs[i] != null && runs[i].good(app);
        }
        String gpu = gpuNeeded ? GpuCheck.last(c).gpu : "";
        int m = 0;
        for (int i = 0; i < 3; i++) {
            String guest = guestKey(oses[i]);
            if (WorksOffer.offer(now, listAt, list, soc, oses[i] == Guest.IOS16 ? "" : gpu, guest, runs[i], app,
                    p.getString("dismissed." + guest, ""), p.getString("opened." + guest, ""))) {
                m |= oses[i] == Guest.IOS26 ? 1 : oses[i] == Guest.IOS16 ? 2 : 4;
            }
        }
        return m;
    }

    /**
     * Runs @then on the screen's thread with the offers up to date: worked out (file reads, off the UI thread) at
     * most once a minute and at once after changed(); in between @then runs at once with the kept result.
     */
    static void refresh(Activity a, Runnable then) {
        Context c = a.getApplicationContext();
        synchronized (WORK) {
            if (!dirty && workedAt != 0 && android.os.SystemClock.elapsedRealtime() - workedAt < WORK_OUT_EVERY_MS) {
                then.run();
                return;
            }
        }
        Thread t = new Thread(() -> {
            synchronized (WORK) {
                // cleared before reading, so a change while this works makes the next refresh work again
                dirty = false;
                try {
                    offered = workOut(c);
                } catch (RuntimeException e) {
                    offered = 0;
                    AppLog.note(c, "tested phones: offer not worked out: " + e);
                }
                workedAt = android.os.SystemClock.elapsedRealtime();
            }
            a.runOnUiThread(() -> {
                if (!a.isDestroyed()) {
                    then.run();
                }
            });
        }, "works-offer");
        t.setDaemon(true);
        t.start();
    }

    private static void mark(Context c, String key, int os) {
        prefs(c).edit().putString(key + guestKey(os), version(c)).apply();
        offered &= ~(os == Guest.IOS26 ? 1 : os == Guest.IOS16 ? 2 : 4);
        changed();
    }

    /** The dialog of the home line for @os: what the issue would say, "Open on GitHub" or "Not now"; @then redraws the home screen. */
    static void show(Activity a, int os, Runnable then) {
        Context c = a.getApplicationContext();
        new Thread(() -> {
            String guest = guestKey(os);
            WorksRun r = run(c, guest);
            if (r == null) {
                return;
            }
            GpuCheck.Result g = TestedPhones.IOS16.equals(guest) ? null : GpuCheck.last(c);
            WorksText.In in = new WorksText.In();
            in.guest = guest;
            in.run = r;
            in.app = CrashReport.appVersion(c);
            in.android = Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
            }
            in.phoneRam = String.format(Locale.ROOT, "%.1f GB", mi.totalMem / 1e9);
            in.soc = soc();
            in.gpu = g == null ? "" : g.gpu;
            in.driver = g == null ? "" : g.driver;
            in.counter = CounterFreq.text().replace("counter: ", "");
            in.setup = SetupTimes.fromJson(prefs(c).getString(os == Guest.IOS16 ? "setup.ios16" : "setup.a26", ""));
            String maker = Build.MANUFACTURER == null || Build.MANUFACTURER.isEmpty() ? ""
                    : Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1);
            String title = WorksText.title(maker + " " + Build.MODEL, in.soc, in.gpu, guest);
            String body = WorksText.fit(CrashReport.REPO, title, WorksText.facts(in), CrashReport.MAX_URL);
            String url = CrashText.issueUrl(CrashReport.REPO, title, body);
            a.runOnUiThread(() -> {
                if (a.isDestroyed()) {
                    return;
                }
                new AlertDialog.Builder(a)
                        .setTitle("Add this phone to the list")
                        .setMessage("This is what the report will say. It opens as a new issue on GitHub, where you read it and"
                                + " submit it, or not; the app sends nothing.\n\n" + title + "\n\n" + body)
                        .setPositiveButton("Open on GitHub", (d, w) -> {
                            mark(c, "opened.", os);
                            AppLog.note(c, "tested phones: works report opened for " + guest);
                            CrashReport.open(a, url);
                            then.run();
                        })
                        .setNegativeButton("Not now", (d, w) -> {
                            mark(c, "dismissed.", os);
                            then.run();
                        })
                        .show();
            });
        }, "works-report").start();
    }
}
