package com.asoft.ios;

/**
 * When a running setup is held (the helper VM stopped with SIGSTOP, an iOS boot not started) for the phone's
 * heat or battery, and when it goes on. A pure state machine (HoldRuleTest): the caller reads Android's thermal
 * status and the battery and asks decide() every few hundred ms.
 *
 * Heat. Android's status runs 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. The
 * S25 Ultra sits at 1 and 2 through a whole setup on the charger (skin 40-42 C: phone-setup-inapp-metrics-2026-11-08.log,
 * IOS26-PHONE-PERF.md), so moderate is the phone's normal working state and never holds. Severe held (2026-11-09:
 * thermal severe, 44 C, slowed a guest until DataMigration's hang limit panicked it). Before 0.1.6 a hold that
 * began at severe lasted until the status was light or cooler: a phone that settles at moderate (several
 * Motorola and Samsung ones do, with the charger in) never released it (A#63).
 *   - severe: hold, and release again at moderate or cooler;
 *   - helper VM only (@bounded): a hold at severe lasts at most SEVERE_HOLD_MS, then the VM runs for
 *     SEVERE_RUN_MS before holding again, so a phone that stays at severe still moves;
 *   - critical and worse: always hold, no limit (the phone is about to cut the apps itself).
 * A release is not undone for MIN_RUN_MS (a status flickering around a threshold does not stop and start the VM
 * twice a second). Battery: below 15 % and not charging holds until the charger is in (unchanged).
 */
final class HoldRule {
    static final int SEVERE = 3, CRITICAL = 4;
    static final long SEVERE_HOLD_MS = 10 * 60_000L, SEVERE_RUN_MS = 2 * 60_000L, MIN_RUN_MS = 30_000L;

    enum Why { NONE, HOT, BATTERY }

    /** The answer: whether to hold, why, and the status it was decided on. */
    static final class Hold {
        final boolean holding;
        final Why why;
        final int thermal;

        Hold(boolean holding, Why why, int thermal) {
            this.holding = holding;
            this.why = why;
            this.thermal = thermal;
        }

        /** What the setup screen and the notification say: plain words, the reason and the status Android reports. */
        String note() {
            if (!holding) {
                return null;
            }
            if (why == Why.BATTERY) {
                return "Paused: the battery is low and the phone is not charging. Plug in the charger and it goes on.";
            }
            return "Paused: the phone is hot (Android says: " + thermalName(thermal) + "). It goes on by itself when it cools down.";
        }

        /** The same for the app's log. */
        String log() {
            return why == Why.BATTERY ? "the battery is low" : "the phone is hot, thermal " + thermal + " (" + thermalName(thermal) + ")";
        }
    }

    private final boolean bounded;
    private boolean heat;           // holding for heat now (a run window of a bounded hold is not holding)
    private boolean window;         // inside a bounded hold's run window
    private long since;             // when the current heat hold, or run window, began
    private long releasedAt = -MIN_RUN_MS;

    /** @bounded true for the helper VM (severe holds at most SEVERE_HOLD_MS at a time), false for the boot gate. */
    HoldRule(boolean bounded) {
        this.bounded = bounded;
    }

    /** @thermal Android's status, @lowBattery below 15 % off the charger, @nowMs any monotonic clock. */
    synchronized Hold decide(int thermal, boolean lowBattery, long nowMs) {
        if (thermal >= CRITICAL) {
            if (!heat) {
                heat = true;
                since = nowMs;
            }
            window = false;
        } else if (thermal >= SEVERE) {
            if (heat) {
                if (bounded && nowMs - since >= SEVERE_HOLD_MS) {
                    release(nowMs);
                    window = true;
                }
            } else if (window) {
                if (nowMs - since >= SEVERE_RUN_MS) {
                    window = false;
                    heat = true;
                    since = nowMs;
                }
            } else if (nowMs - releasedAt >= MIN_RUN_MS) {
                heat = true;
                since = nowMs;
            }
        } else {
            // moderate or cooler: nothing to wait for
            if (heat) {
                release(nowMs);
            }
            window = false;
        }
        if (heat) {
            return new Hold(true, Why.HOT, thermal);
        }
        if (lowBattery) {
            return new Hold(true, Why.BATTERY, thermal);
        }
        return new Hold(false, Why.NONE, thermal);
    }

    private void release(long nowMs) {
        heat = false;
        releasedAt = nowMs;
        since = nowMs;
    }

    /** Android's name for a thermal status. */
    static String thermalName(int t) {
        switch (t) {
            case 0: return "none";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return "unknown";
        }
    }
}
