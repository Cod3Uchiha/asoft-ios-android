package com.asoft.ios;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The record of a setup boot's DataMigration that needs no answer from the guest (K18: a second boot whose
 * shell goes silent at minute 50, see notes/handover-2026-11-13/k18-stall.md, section 6): one line every 5 min and at
 * the first no-answer, from what the console and QEMU's stderr say, and 64 KB of the console before and after
 * the first jump of the pids it prints or the first no-answer, kept in the setup's log folder.
 *
 * Not recorded, for want of a cheap source: the tick's own lateness (the loop's sleeps are not planned
 * against a clock) and the guest's real pid counter (the console prints a pid only in some lines).
 */
final class SetupTick {
    /** A pid this much higher than the last tick's is a jump (a healthy boot ends near 1,300; a churning one passes 65,000). */
    static final int JUMP = 2000;
    static final int SLICE = 64 << 10;
    /** At most this much of a file is read per tick (the newest part, when more came). */
    static final int READ_CAP = 4 << 20;

    private static final Pattern PID = Pattern.compile("pid:\\s?(\\d+)");
    private static final Pattern BASH = Pattern.compile("launching proc\\[[^\\]]*\\]: /bin/bash");
    private static final Pattern STAMP = Pattern.compile("waited \\d+s for stamp");
    private static final Pattern SAMPLER = Pattern.compile("el-sample t=(\\d+)s");

    /** What one tick's new console text holds. */
    static final class Console {
        int maxPid, bash, displayStarts, gpuRestarts;
        /** Offset in the text of the first pid more than JUMP above @prevMax, -1 when none. */
        int jumpAt = -1;
    }

    static Console scanConsole(String text, int prevMaxPid) {
        Console c = new Console();
        Matcher m = PID.matcher(text);
        while (m.find()) {
            int pid;
            try {
                pid = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid > c.maxPid) {
                c.maxPid = pid;
            }
            if (c.jumpAt < 0 && pid > prevMaxPid + JUMP) {
                c.jumpAt = m.start();
            }
        }
        c.bash = count(BASH, text);
        c.displayStarts = count(Pattern.compile("No Default FB Surface found"), text);
        c.gpuRestarts = count(Pattern.compile("Trying to restart GPU"), text);
        return c;
    }

    static int count(Pattern p, String text) {
        int n = 0;
        Matcher m = p.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** The last "el-sample t=Ns" of QEMU's stderr text: N, or @was when it has none. */
    static long samplerT(String stderr, long was) {
        Matcher m = SAMPLER.matcher(stderr);
        long t = was;
        while (m.find()) {
            t = Long.parseLong(m.group(1));
        }
        return t;
    }

    // -- the files ----------------------------------------------------------------------------------------

    private final File serial, stderr, logs;
    private final String name;
    private long serialAt, stderrAt, samplerT = -1;
    private int maxPid, framesAt;
    private boolean jumpKept, silentKept;
    private File afterFile;
    private long afterFrom;

    /** @name: the prefix of the slices' files ("wallpaper"); @logs: the setup's log folder. */
    SetupTick(File serial, File stderr, File logs, String name, int framesNow) {
        this.serial = serial;
        this.stderr = stderr;
        this.logs = logs;
        this.name = name;
        this.framesAt = framesNow;
    }

    /** The text of @f after @from (at most READ_CAP of it, the newest part); @from[0] becomes the file's length. */
    private static String readFrom(File f, long[] from) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long at = from[0] > len ? 0 : from[0];
            long n = len - at;
            if (n > READ_CAP) {
                at = len - READ_CAP;
                n = READ_CAP;
            }
            byte[] b = new byte[(int) n];
            r.seek(at);
            r.readFully(b);
            from[0] = len;
            return new String(b, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The tick's line for minute @min of the DataMigration loop: what the console and stderr gained since the last
     * tick. @frames: iOS's frames shown so far; @suspendS: seconds the phone slept since the boot began; @wallS:
     * seconds since QEMU started (the sampler's own clock minus this one grows when QEMU is frozen).
     */
    String tick(int min, String why, int frames, long suspendS, long wallS) {
        if (afterFile != null) {
            // what followed the cut since it was made (the cut's own tick set it: this is the next one)
            slice(serial, afterFrom, afterFile);
            afterFile = null;
        }
        long[] sa = {serialAt};
        long before = serialAt;
        String console = readFrom(serial, sa);
        long consoleBytes = sa[0] - before;
        long start = sa[0] - console.length();       // the text's first byte in the file
        serialAt = sa[0];
        Console c = scanConsole(console, maxPid);
        if (c.jumpAt >= 0 && !jumpKept) {
            jumpKept = true;
            keep("pidjump", start + c.jumpAt);
        }
        maxPid = Math.max(maxPid, c.maxPid);
        long[] ea = {stderrAt};
        String err = readFrom(stderr, ea);
        stderrAt = ea[0];
        int faulted = count(Pattern.compile("exec faulted"), err);
        int stamps = count(STAMP, err);
        samplerT = samplerT(err, samplerT);
        int newFrames = frames - framesAt;
        framesAt = frames;
        return String.format(Locale.ROOT, "tick %d min (%s): console pid max %d, bash launches %d, display starts %d, \"restart GPU\" %d,"
                        + " console +%d bytes, frames +%d, reims exec-faulted %d, stamp-waits %d, sampler t=%s s of %d s since QEMU started,"
                        + " phone asleep %d s",
                min, why, maxPid, c.bash, c.displayStarts, c.gpuRestarts, consoleBytes, newFrames, faulted, stamps,
                samplerT < 0 ? "?" : String.valueOf(samplerT), wallS, suspendS);
    }

    /** The shell has just not answered: the console up to now is kept, what follows it at the next tick. */
    void silent() {
        if (silentKept) {
            return;
        }
        silentKept = true;
        long cut = serial.length();
        keep("noanswer", cut);
    }

    /** The 64 KB before @cut now; the 64 KB from @cut when they are there (a cut in the past), else at the next tick. */
    private void keep(String why, long cut) {
        logs.mkdirs();
        long from = Math.max(0, cut - SLICE);
        slice(serial, from, cut - from, new File(logs, name + "-serial-" + why + "-before.txt"));
        File after = new File(logs, name + "-serial-" + why + "-after.txt");
        if (serial.length() - cut >= SLICE) {
            slice(serial, cut, SLICE, after);
        } else {
            afterFile = after;
            afterFrom = cut;
        }
    }

    private static void slice(File src, long from, File dst) {
        slice(src, from, SLICE, dst);
    }

    /** @len bytes of @src from @from (fewer at its end) into @dst. */
    static void slice(File src, long from, long len, File dst) {
        try (RandomAccessFile r = new RandomAccessFile(src, "r")) {
            long n = Math.max(0, Math.min(len, r.length() - from));
            byte[] b = new byte[(int) n];
            r.seek(from);
            r.readFully(b);
            java.nio.file.Files.write(dst.toPath(), b);
        } catch (IOException ignored) {
            // the slice is a help for a later question: no file is no harm
        }
    }
}
