package com.asoft.ios;

/**
 * How late a waiting loop's sleeps ran (K18: a frozen or dozing app wakes late). The caller measures each sleep
 * on the monotonic clock that counts the phone's sleep too (SystemClock.elapsedRealtime) and gives the planned
 * and the real length; the worst since the last line is read and reset by takeMax().
 */
final class LoopLate {
    private long maxMs, lastMs;

    /** One sleep of @plannedMs that took @actualMs. Returns how late it was (0 when on time or early). */
    synchronized long sleep(long plannedMs, long actualMs) {
        lastMs = Math.max(0, actualMs - plannedMs);
        maxMs = Math.max(maxMs, lastMs);
        return lastMs;
    }

    /** The worst lateness since the last call; starts over. */
    synchronized long takeMax() {
        long m = maxMs;
        maxMs = 0;
        return m;
    }

    /** The last sleep's lateness. */
    synchronized long last() {
        return lastMs;
    }
}
