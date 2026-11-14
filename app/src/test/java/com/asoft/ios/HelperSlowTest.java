package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HelperSlowTest {
    @Test
    public void aKilledRunStepsDownOneSizePerKill() {
        // a 4 GB phone: 1536 MiB for iOS 16's jobs; after the memory killer once, 1024
        assertEquals(1536, Helper.stepDown(1536, 0, 1024));
        assertEquals(1024, Helper.stepDown(1536, 1, 1024));
        assertEquals(1024, Helper.stepDown(1536, 3, 1024));
        // an 8 GB phone: 3072, 2048, 1536, 1024
        assertEquals(2048, Helper.stepDown(3072, 1, 1024));
        assertEquals(1536, Helper.stepDown(3072, 2, 1024));
        assertEquals(1024, Helper.stepDown(3072, 3, 1024));
    }

    @Test
    public void theJobFloorsHold() {
        // the iOS 26 jobs keep their files in RAM: never below their floor
        assertEquals(2048, Helper.stepDown(3072, 4, Helper.FLOOR_WORK_IN_RAM));
        assertEquals(2048, Helper.stepDown(2048, 1, Helper.FLOOR_WORK_IN_RAM));
        assertEquals(3072, Helper.stepDown(4096, 1, Helper.FLOOR_WORK_IN_RAM));
    }

    @Test
    public void theRetryStepsAreAsBefore() {
        // smaller() with the iOS 16 floor does not reach the after-kill size
        assertEquals(1536, Helper.smaller(1536, Helper.FLOOR_IOS16));
        assertEquals(1536, Helper.smaller(2048, Helper.FLOOR_IOS16));
        assertEquals(1024, Helper.smaller(1536, Helper.FLOOR_IOS16_AFTER_KILL));
    }

    @Test
    public void theProbeLineSaysWhereTheTimeGoes() {
        // 8 cores busy for 120 s = 960 s of CPU = 96,000 ticks of 10 ms
        String l = Helper.probeLine(40, 1500 * 1024L, 96_000, 120_000, 1400 * 1024L, "2 (moderate)", "0-7", "3.50 %");
        assertTrue(l, l.startsWith("40 min: QEMU RSS 1500 MB, busy 800 % of one core, MemAvailable 1400 MB"));
        assertTrue(l, l.contains("thermal 2 (moderate), cpuset 0-7, memory pressure 3.50 %"));
        assertTrue(Helper.probeLine(1, -1, -1, 120_000, -1, "x", "-", "n/a").contains("busy ?"));
    }
}
