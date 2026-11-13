package com.asoft.ios;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of ending a setup boot that need no Android (PhoneSetup26.saveBoot uses them): when the guest's
 * disk has been quiet long enough to stop it without its shell's sync, and the numbers read from QEMU's
 * answers (query-blockstats, qemu-img check).
 */
final class SetupEnd {
    private SetupEnd() {}

    /** No write for this long counts as quiet: a journal commit and a syncer pass (iOS flushes every ~30 s) are done by then. */
    static final long IDLE_MS = 15_000;
    /** A guest that never stops writing (a crash-report loop) is stopped after this long anyway. */
    static final long CEILING_MS = 120_000;
    static final long POLL_MS = 2_000;

    enum Verdict { WAIT, QUIET, CEILING }

    /** Watches the disk's write count: quiet = the count did not move for idleMs; ceiling = ceilingMs since the first look. */
    static final class Idle {
        private final long idleMs, ceilingMs;
        private long start = -1, lastChange = -1, lastWrites = -1;
        private boolean known;
        private long first = -1;

        Idle(long idleMs, long ceilingMs) {
            this.idleMs = idleMs;
            this.ceilingMs = ceilingMs;
        }

        /** The write count at @now; -1 when QEMU did not answer (that never counts as quiet). */
        void sample(long now, long writes) {
            if (start < 0) {
                start = now;
            }
            if (writes < 0) {
                return;
            }
            if (!known) {
                known = true;
                first = writes;
                lastWrites = writes;
                lastChange = now;
            } else if (writes != lastWrites) {
                lastWrites = writes;
                lastChange = now;
            }
        }

        Verdict verdict(long now) {
            if (known && now - lastChange >= idleMs) {
                return Verdict.QUIET;
            }
            return start >= 0 && now - start >= ceilingMs ? Verdict.CEILING : Verdict.WAIT;
        }

        /** The writes counted since the first look, -1 when none was read. */
        long writesSince() {
            return known ? lastWrites - first : -1;
        }
    }

    /** The sum of every "@key": N in a QMP reply (a disk and the images under it each have stats), -1 when there is none. */
    static long sum(String reply, String key) {
        if (reply == null) {
            return -1;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(reply);
        long n = -1;
        while (m.find()) {
            n = Math.max(n, 0) + Long.parseLong(m.group(1));
        }
        return n;
    }

    /** "corruptions" from qemu-img check --output=json; -1 when the output has none. */
    static long corruptions(String json) {
        return sum(json, "corruptions");
    }
}
