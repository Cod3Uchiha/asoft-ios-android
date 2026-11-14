package com.asoft.ios;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class WorksOfferTest {
    private static final long NOW = 1_800_000_000_000L, DAY = 24 * 3600_000L;
    private static final String APP = "0.1.6";
    private static final List<TestedPhones.Entry> LIST = TestedPhones.parse(TestedPhonesTest.SEED);

    private static WorksRun good() {
        WorksRun r = new WorksRun();
        r.guest = "ios26";
        r.app = APP;
        r.firstFrameS = 130;
        r.aliveS = 700;
        return r;
    }

    /** A phone that is not on the list, a fresh list, a good run, nothing dismissed or opened: the offer. */
    private static boolean offer(long listAt, List<TestedPhones.Entry> list, String soc, String gpu, String guest, WorksRun run,
            String dismissed, String opened) {
        return WorksOffer.offer(NOW, listAt, list, soc, gpu, guest, run, APP, dismissed, opened);
    }

    private static boolean base(WorksRun run) {
        return offer(NOW - DAY, LIST, "SM8650", "Adreno (TM) 750", "ios26", run, "", "");
    }

    @Test
    public void offersWhenEverythingHolds() {
        assertTrue(base(good()));
    }

    @Test
    public void noListNoOffer() {
        assertFalse(offer(NOW - DAY, null, "SM8650", "Adreno (TM) 750", "ios26", good(), "", ""));
        assertFalse(offer(0, LIST, "SM8650", "Adreno (TM) 750", "ios26", good(), "", ""));
    }

    @Test
    public void aStaleListGivesNoOffer() {
        assertTrue(offer(NOW - 7 * DAY, LIST, "SM8650", "Adreno (TM) 750", "ios26", good(), "", ""));
        assertFalse(offer(NOW - 7 * DAY - 1, LIST, "SM8650", "Adreno (TM) 750", "ios26", good(), "", ""));
        // fetched "in the future": the clock was set back
        assertFalse(offer(NOW + 1000, LIST, "SM8650", "Adreno (TM) 750", "ios26", good(), "", ""));
    }

    @Test
    public void aKeyListedAsWorksOrFailsIsNotOffered() {
        assertFalse(offer(NOW - DAY, LIST, "SM8750", "adreno 830", "ios26", good(), "", ""));
        assertFalse(offer(NOW - DAY, LIST, "MT6985", "Mali-G720", "ios26", good(), "", ""));
    }

    @Test
    public void ios16IsOfferedOnSocAloneEvenWithoutAGpuName() {
        WorksRun r = good();
        r.guest = "ios16";
        assertTrue(offer(NOW - DAY, LIST, "SM8650", "", "ios16", r, "", ""));
        assertFalse(offer(NOW - DAY, LIST, "SM8750", "", "ios16", r, "", ""));
        // iOS 26 needs the GPU's name for its key
        assertFalse(offer(NOW - DAY, LIST, "SM8650", "", "ios26", good(), "", ""));
        assertFalse(offer(NOW - DAY, LIST, "", "", "ios16", r, "", ""));
    }

    @Test
    public void noRunNoOffer() {
        assertFalse(base(null));
    }

    @Test
    public void aRunThatWasTooShortDoesNotCount() {
        WorksRun r = good();
        r.aliveS = 599;
        assertFalse(base(r));
        r.aliveS = 600;
        assertTrue(base(r));
    }

    @Test
    public void noFirstFrameNoOffer() {
        WorksRun r = good();
        r.firstFrameS = -1;
        assertFalse(base(r));
    }

    @Test
    public void aCrashRecordEndsIt() {
        WorksRun r = good();
        r.crashed = true;
        assertFalse(base(r));
    }

    @Test
    public void dismissedOrOpenedForThisVersionIsNotOfferedAgain() {
        assertFalse(offer(NOW - DAY, LIST, "SM8650", "Adreno (TM) 750", "ios26", good(), APP, ""));
        assertFalse(offer(NOW - DAY, LIST, "SM8650", "Adreno (TM) 750", "ios26", good(), "", APP));
        // a "Not now" of an older version does not carry over
        assertTrue(offer(NOW - DAY, LIST, "SM8650", "Adreno (TM) 750", "ios26", good(), "0.1.5", "0.1.5"));
    }

    @Test
    public void aRunOfAnotherAppVersionDoesNotCount() {
        WorksRun r = good();
        r.app = "0.1.5";
        assertFalse(base(r));
    }

    @Test
    public void goodRunRoundTripsThroughItsFile() {
        WorksRun r = good();
        r.resumed = true;
        r.coldS = 125;
        r.loadS = 20;
        r.fps = 12.5;
        r.halted = 3;
        r.el1 = 60;
        r.screen = "3x (1179x2556)";
        r.ramMiB = 4096;
        r.thermalMax = 2;
        r.lowestAvailMb = 2100;
        WorksRun b = WorksRun.fromJson(r.toJson());
        assertTrue(b.good(APP));
        org.junit.Assert.assertEquals(125, b.coldS);
        org.junit.Assert.assertEquals(20, b.loadS);
        org.junit.Assert.assertEquals(12.5, b.fps, 0.001);
        org.junit.Assert.assertEquals("3x (1179x2556)", b.screen);
        org.junit.Assert.assertEquals(2100, b.lowestAvailMb);
        org.junit.Assert.assertNull(WorksRun.fromJson("{not json"));
        org.junit.Assert.assertNull(WorksRun.fromJson("{}"));
    }
}
