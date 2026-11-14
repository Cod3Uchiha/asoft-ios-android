package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorksRunTest {
    private static WorksRun run() {
        WorksRun r = new WorksRun();
        r.guest = "ios26";
        r.app = "0.1.6";
        r.firstFrameS = 130;
        r.aliveS = 600;
        return r;
    }

    @Test
    public void goodNeedsThisAppVersionAFirstFrameTenMinutesAndNoCrash() {
        assertTrue(run().good("0.1.6"));
        assertFalse(run().good("0.1.7"));
        WorksRun r = run();
        r.firstFrameS = -1;
        assertFalse(r.good("0.1.6"));
        r = run();
        r.firstFrameS = 0;
        assertTrue(r.good("0.1.6"));
        r = run();
        r.aliveS = WorksRun.GOOD_ALIVE_S - 1;
        assertFalse(r.good("0.1.6"));
        r = run();
        r.crashed = true;
        assertFalse(r.good("0.1.6"));
    }

    @Test
    public void roundTripKeepsEveryField() {
        WorksRun r = run();
        r.resumed = true;
        r.crashed = true;
        r.coldS = 125;
        r.loadS = 20;
        r.fps = 12.5;
        r.peakFps = 9;
        r.halted = 3;
        r.el1 = 60;
        r.el0tcg = 7;
        r.el0nat = 30;
        r.ramMiB = 4096;
        r.screen = "2x (786x1704)";
        r.thermalMax = 3;
        r.lowestAvailMb = 1900;
        WorksRun b = WorksRun.fromJson(r.toJson());
        assertNotNull(b);
        assertEquals("ios26", b.guest);
        assertEquals("0.1.6", b.app);
        assertTrue(b.resumed);
        assertTrue(b.crashed);
        assertEquals(130, b.firstFrameS);
        assertEquals(600, b.aliveS);
        assertEquals(125, b.coldS);
        assertEquals(20, b.loadS);
        assertEquals(12.5, b.fps, 0.001);
        assertEquals(9, b.peakFps);
        assertEquals(3, b.halted, 0.001);
        assertEquals(60, b.el1, 0.001);
        assertEquals(7, b.el0tcg, 0.001);
        assertEquals(30, b.el0nat, 0.001);
        assertEquals(4096, b.ramMiB);
        assertEquals("2x (786x1704)", b.screen);
        assertEquals(3, b.thermalMax);
        assertEquals(1900, b.lowestAvailMb);
    }

    @Test
    public void aFileWithoutPeakFpsParsesWithItUnknown() {
        WorksRun r = run();
        r.peakFps = 7;
        String json = r.toJson();
        assertTrue(json.contains(",\"peakFps\":7"));
        WorksRun b = WorksRun.fromJson(json.replace(",\"peakFps\":7", ""));
        assertNotNull(b);
        assertEquals(-1, b.peakFps);
        assertEquals(-1, run().peakFps);
    }

    @Test
    public void aDamagedFileIsNoRun() {
        String json = run().toJson();
        assertNull(WorksRun.fromJson(null));
        assertNull(WorksRun.fromJson(""));
        assertNull(WorksRun.fromJson(json.substring(0, json.length() / 2)));
        assertNull(WorksRun.fromJson("[1,2]"));
        assertNull(WorksRun.fromJson("{}"));
        assertNull(WorksRun.fromJson(json + "x"));
    }
}
