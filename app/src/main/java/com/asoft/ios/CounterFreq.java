package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone's counter frequency (CNTFRQ_EL0). iOS 26, the watch and the helper VM run on the phone's own
 * counter (x-host-counter), so their idea of its frequency must be the phone's: Snapdragons since the 8 Gen 1
 * say 19.2 MHz, a Dimensity 9500 says 1 GHz (A#66). The guest's device tree carries it in every
 * /cpus/cpuN timebase-frequency (QEMU's vresearch machine takes the guest's CNTFRQ from cpu0's).
 */
final class CounterFreq {
    private CounterFreq() {
    }

    /** What every app version before this assumed, and what the setup's trees (-19m2) carry. */
    static final long DEFAULT_HZ = 19_200_000L;

    private static long cached;
    private static String how = "";
    private static boolean logged, measured;

    /** A counter frequency that is believable (1 MHz up to what QEMU's u32 read of the tree holds), else 0. */
    static long sane(long raw) {
        return raw >= 1_000_000L && raw <= 0xFFFF_FFFFL ? raw : 0;
    }

    /** How long the measurement sleeps: at 26 MHz the two ends are read to well under 1 ppm over this. */
    static final int MEASURE_MS = 300;

    /**
     * A measured rate as the number the phone's kernel has: a counter runs at a round number (19.2, 24, 26 MHz,
     * 1 GHz), so a measurement within 100 ppm of a multiple of 100 kHz is that multiple, which every start then
     * measures again (a quick save names the rate it ran on). Anything else keeps five digits.
     */
    static long rounded(long measured) {
        if (measured <= 0) {
            return 0;
        }
        long near = Math.round(measured / 100_000.0) * 100_000L;
        if (near > 0 && Math.abs(measured - near) * 10_000L <= near) {
            return near;
        }
        long step = 1;
        for (long v = measured; v >= 100_000L; v /= 10) {
            step *= 10;
        }
        return Math.round(measured / (double) step) * step;
    }

    /**
     * The phone's counter frequency in Hz, got once: the CPU's register; when that reads 0 or nonsense (an Exynos
     * 9810 leaves it unset), the counter measured against the clock; DEFAULT_HZ when neither gives a number.
     */
    static synchronized long hz() {
        if (cached == 0) {
            long raw;
            try {
                raw = Render.counterHz();
            } catch (UnsatisfiedLinkError | ExceptionInInitializerError | NoClassDefFoundError e) {
                raw = 0;
            }
            long ok = sane(raw);
            if (ok != 0) {
                cached = ok;
                how = "read from the CPU";
            } else {
                long m;
                try {
                    m = Render.counterMeasuredHz(MEASURE_MS);
                } catch (UnsatisfiedLinkError | ExceptionInInitializerError | NoClassDefFoundError e) {
                    m = 0;
                }
                long r = sane(rounded(m));
                cached = r != 0 ? r : DEFAULT_HZ;
                measured = r != 0;
                how = r != 0 ? "the read gave " + raw + "; measured against the clock: " + m
                        : "the read gave " + raw + ", the measurement " + m + ", using " + DEFAULT_HZ;
            }
        }
        return cached;
    }

    /** Tests: the value hz() answers. */
    static synchronized void setForTest(long hz) {
        cached = hz;
        how = "test";
        logged = false;
        measured = false;
    }

    /** "counter: 19200000 Hz": for the run records and crash reports. */
    static String text() {
        long h = hz();
        return "counter: " + h + " Hz" + (measured ? " (measured: the CPU's register reads 0)" : h == DEFAULT_HZ ? "" : " (not 19.2 MHz)");
    }

    /** Once per app start: the value and where it came from, in the app log. */
    static void logOnce(Context c) {
        long h = hz();
        synchronized (CounterFreq.class) {
            if (logged) {
                return;
            }
            logged = true;
        }
        AppLog.note(c, "counter: " + h + " Hz (" + how + ")");
    }

    // -- the device tree ---------------------------------------------------------------------------------------

    /** Offsets of every /cpus/cpuN timebase-frequency value (a u64) in the Apple device tree @b (dt-phone.py --timebase). */
    static List<Integer> timebaseOffsets(byte[] b) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        List<Integer> out = new ArrayList<>();
        int end;
        try {
            end = walk(bb, 0, "", out);
        } catch (IndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new IOException("not an Apple device tree");
        }
        if (end != b.length) {
            throw new IOException("the tree is " + b.length + " bytes, its nodes end at " + end + ": not an ADT?");
        }
        if (out.isEmpty()) {
            throw new IOException("no /cpus/*/timebase-frequency in the tree");
        }
        return out;
    }

    private static int walk(ByteBuffer bb, int o, String path, List<Integer> out) throws IOException {
        int np = bb.getInt(o), nc = bb.getInt(o + 4);
        o += 8;
        String node = "?";
        int tb = -1;
        for (int i = 0; i < np; i++) {
            int z = 0;
            while (z < 32 && bb.get(o + z) != 0) {
                z++;
            }
            String pn = new String(bb.array(), o, z, StandardCharsets.US_ASCII);
            int ln = bb.getInt(o + 32) & 0x7fffffff;
            if (pn.equals("name")) {
                int k = 0;
                while (k < ln && bb.get(o + 36 + k) != 0) {
                    k++;
                }
                node = new String(bb.array(), o + 36, k, StandardCharsets.US_ASCII);
            } else if (pn.equals("timebase-frequency")) {
                if (ln != 8) {
                    throw new IOException("timebase-frequency is " + ln + " bytes, not 8");
                }
                tb = o + 36;
            }
            o += 36 + ((ln + 3) & ~3);
        }
        String here = path.isEmpty() ? node : path + "/" + node;
        if (tb >= 0 && here.contains("/cpus/")) {
            out.add(tb);
        }
        for (int i = 0; i < nc; i++) {
            o = walk(bb, o, here, out);
        }
        return o;
    }

    /** @tree with every CPU's timebase-frequency at @hz; @tree itself when they all are already. */
    static byte[] withTimebase(byte[] tree, long hz) throws IOException {
        List<Integer> at = timebaseOffsets(tree);
        ByteBuffer bb = ByteBuffer.wrap(tree).order(ByteOrder.LITTLE_ENDIAN);
        boolean same = true;
        for (int o : at) {
            same &= bb.getLong(o) == hz;
        }
        if (same) {
            return tree;
        }
        byte[] b = tree.clone();
        ByteBuffer out = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (int o : at) {
            out.putLong(o, hz);
        }
        return b;
    }

    /**
     * The device tree to start with: @src when its timebase-frequency is @hz already (nothing is written), else
     * a copy in @outDir (NAME-tbHZ, written when missing or older) with every CPU's at @hz. The -19m2 trees
     * (setup, bundles from a PC or another phone) keep their names and stay as they are.
     */
    static File fit(File outDir, File src, long hz) throws IOException {
        byte[] b = Files.readAllBytes(src.toPath());
        byte[] p = withTimebase(b, hz);
        if (p == b) {
            return src;
        }
        File out = new File(outDir, src.getName() + "-tb" + hz);
        if (out.isFile() && out.lastModified() >= src.lastModified() && out.length() == p.length) {
            return out;
        }
        File tmp = new File(out.getPath() + ".tmp");
        Files.write(tmp.toPath(), p);
        if (!tmp.renameTo(out)) {
            throw new IOException("cannot write " + out);
        }
        return out;
    }

    // -- the clock note of a quick save ----------------------------------------------------------------------

    /** @base (a save's clock note) for a guest on a @hz counter: unchanged at 19.2 MHz, so existing saves stay as they are. */
    static String clockNote(String base, long hz) {
        return hz == DEFAULT_HZ ? base : base + "@" + hz;
    }

    /** The counter frequency a save's clock note says it ran on: 19.2 MHz without a "@HZ" (every save before this). */
    static long noteHz(String note) {
        int i = note == null ? -1 : note.indexOf('@');
        if (i < 0) {
            return DEFAULT_HZ;
        }
        try {
            return Long.parseLong(note.substring(i + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
