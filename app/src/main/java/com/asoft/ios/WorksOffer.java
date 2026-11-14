package com.asoft.ios;

import java.util.List;

/**
 * Whether home offers the "works" report for a guest. Pure (WorksOfferTest). All of these, else it stays silent:
 * a list fetched within the last week (no list, no offer: so it is silent offline and until devices.json exists);
 * no "works" row of that list for this phone and guest (a "fails" row does not count: a phone that runs anyway
 * corrects it, so its report is wanted); a good run
 * of the guest on this app version (WorksRun.good); the user has not said "Not now" for this guest and app version
 * and has not opened the report for it.
 */
final class WorksOffer {
    private WorksOffer() {}

    /** How old the fetched list may be. */
    static final long LIST_MAX_AGE_MS = 7 * 24 * 3600_000L;

    /**
     * @param listAtMs     when the list was fetched (wall clock), 0 = never
     * @param list         the parsed list, null = none (or a bad file)
     * @param soc          the phone's SoC ("" = unknown)
     * @param gpu          the GPU name of the last GPU check ("" = unknown; iOS 16 does not need it)
     * @param run          the guest's kept run, null = none
     * @param dismissedApp the app version the user said "Not now" on for this guest ("" = never)
     * @param openedApp    the app version the user opened the report on for this guest ("" = never)
     */
    static boolean offer(long nowMs, long listAtMs, List<TestedPhones.Entry> list, String soc, String gpu, String guest,
            WorksRun run, String app, String dismissedApp, String openedApp) {
        long age = nowMs - listAtMs;
        // a clock set back counts as an old list
        if (list == null || listAtMs <= 0 || age < 0 || age > LIST_MAX_AGE_MS) {
            return false;
        }
        if (soc.isEmpty() || !TestedPhones.IOS16.equals(guest) && gpu.isEmpty()) {
            return false;
        }
        // only a "works" row silences it: a phone of a family listed as failing that demonstrably runs is the report we want most
        if (TestedPhones.lookup(list, soc, gpu, guest) == TestedPhones.Verdict.WORKS) {
            return false;
        }
        if (run == null || !run.good(app)) {
            return false;
        }
        return !app.equals(dismissedApp) && !app.equals(openedApp);
    }
}
