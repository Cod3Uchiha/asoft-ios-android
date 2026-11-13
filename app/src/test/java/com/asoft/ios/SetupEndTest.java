package com.asoft.ios;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SetupEndTest {
    private static SetupEnd.Idle idle() {
        return new SetupEnd.Idle(15_000, 120_000);
    }

    @Test
    public void aDiskThatStopsWritingIsQuietAfterTheIdleTime() {
        SetupEnd.Idle i = idle();
        i.sample(0, 100);
        assertEquals(SetupEnd.Verdict.WAIT, i.verdict(0));          // the first look never says quiet
        i.sample(2_000, 130);
        i.sample(4_000, 130);
        assertEquals(SetupEnd.Verdict.WAIT, i.verdict(4_000));
        i.sample(16_000, 130);
        assertEquals(SetupEnd.Verdict.WAIT, i.verdict(16_000));     // 14 s since the last change (130 came at 2 s)
        i.sample(17_000, 130);
        assertEquals(SetupEnd.Verdict.QUIET, i.verdict(17_000));    // 15 s
        assertEquals(30, i.writesSince());
    }

    @Test
    public void aWriteStartsTheWaitAgain() {
        SetupEnd.Idle i = idle();
        i.sample(0, 10);
        i.sample(14_000, 10);
        i.sample(14_500, 11);
        i.sample(29_000, 11);
        assertEquals(SetupEnd.Verdict.WAIT, i.verdict(29_000));
        i.sample(29_500, 11);
        assertEquals(SetupEnd.Verdict.QUIET, i.verdict(29_500));
    }

    @Test
    public void aDiskThatNeverStopsIsStoppedAtTheCeiling() {
        SetupEnd.Idle i = idle();
        long w = 0;
        for (long t = 0; t < 119_000; t += 2_000) {
            i.sample(t, w += 5);
            assertEquals(SetupEnd.Verdict.WAIT, i.verdict(t));
        }
        i.sample(120_000, w += 5);
        assertEquals(SetupEnd.Verdict.CEILING, i.verdict(120_000));
    }

    @Test
    public void noAnswerFromQemuIsNeverQuiet() {
        SetupEnd.Idle i = idle();
        for (long t = 0; t < 120_000; t += 2_000) {
            i.sample(t, -1);
            assertEquals(SetupEnd.Verdict.WAIT, i.verdict(t));
        }
        i.sample(120_000, -1);
        assertEquals(SetupEnd.Verdict.CEILING, i.verdict(120_000));
        assertEquals(-1, i.writesSince());
    }

    @Test
    public void answersThatComeLateStillCount() {
        SetupEnd.Idle i = idle();
        i.sample(0, -1);
        i.sample(2_000, -1);
        i.sample(4_000, 50);
        assertEquals(SetupEnd.Verdict.WAIT, i.verdict(18_999));
        i.sample(19_000, 50);
        assertEquals(SetupEnd.Verdict.QUIET, i.verdict(19_000));
    }

    @Test
    public void statsAreSummedOverEveryDiskAndNotMixedUp() {
        String reply = "{\"return\": [{\"device\": \"drv0\", \"stats\": {\"rd_operations\": 5, \"wr_operations\": 7,"
                + " \"failed_wr_operations\": 1, \"flush_operations\": 3, \"failed_flush_operations\": 0},"
                + " \"parent\": {\"stats\": {\"wr_operations\": 11, \"flush_operations\": 2}}},"
                + " {\"device\": \"ramdisk\", \"stats\": {\"wr_operations\":0}}]}";
        assertEquals(18, SetupEnd.sum(reply, "wr_operations"));
        assertEquals(1, SetupEnd.sum(reply, "failed_wr_operations"));
        assertEquals(5, SetupEnd.sum(reply, "flush_operations"));
        assertEquals(0, SetupEnd.sum(reply, "failed_flush_operations"));
        assertEquals(-1, SetupEnd.sum(reply, "nothing_here"));
        assertEquals(-1, SetupEnd.sum(null, "wr_operations"));
    }

    @Test
    public void qemuImgCheckCorruptions() {
        assertEquals(0, SetupEnd.corruptions("{\"check-errors\": 0, \"corruptions\": 0, \"leaks\": 2}"));
        assertEquals(3, SetupEnd.corruptions("{\n    \"corruptions\": 3,\n    \"leaks\": 0\n}"));
        assertEquals(-1, SetupEnd.corruptions("qemu-img: Could not open"));
    }
}
