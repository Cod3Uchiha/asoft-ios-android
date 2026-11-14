package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class WorksTextTest {
    private static final String REPO = "alpersamur3/asoft-ios-android";

    private static WorksText.In in() {
        WorksText.In in = new WorksText.In();
        in.guest = "ios26";
        in.app = "0.1.6 (8)";
        in.android = "16 (SDK 36)";
        in.phoneRam = "11.7 GB";
        in.soc = "SM8750";
        in.gpu = "Adreno (TM) 830";
        in.driver = "Turnip 25.1";
        in.counter = "19200000 Hz";
        WorksRun r = new WorksRun();
        r.guest = "ios26";
        r.app = "0.1.6";
        r.firstFrameS = 130;
        r.aliveS = 725;
        r.coldS = 130;
        r.loadS = 22;
        r.fps = 14.25;
        r.halted = 3;
        r.el1 = 60;
        r.el0tcg = 7;
        r.el0nat = 30;
        r.ramMiB = 4096;
        r.screen = "3x (1179x2556)";
        r.thermalMax = 2;
        r.lowestAvailMb = 2400;
        in.run = r;
        SetupTimes t = new SetupTimes(0);
        t.step("Download", 0);
        t.step("Build the disk", 1_560_000);
        t.finish(4_500_000);
        in.setup = SetupTimes.fromJson(t.toJson());
        return in;
    }

    @Test
    public void titleNamesPhoneChipGpuAndGuest() {
        assertEquals("Works: Samsung SM-S938B (SM8750, Adreno (TM) 830): iOS 26",
                WorksText.title("Samsung SM-S938B", "SM8750", "Adreno (TM) 830", "ios26"));
        assertEquals("Works: Samsung SM-S938B (SM8750, Adreno (TM) 830): watchOS",
                WorksText.title("Samsung SM-S938B", "SM8750", "Adreno (TM) 830", "watch"));
        assertEquals("Works: Pixel 9 (Tensor G4): iOS 16", WorksText.title("Pixel 9", "Tensor G4", "", "ios16"));
    }

    @Test
    public void bodyHasTheIntroTheFactsOnePerLineAndTheEmptyClosingLine() {
        String body = WorksText.body(WorksText.facts(in()));
        assertTrue(body.startsWith(WorksText.INTRO + "\n\nApp: 0.1.6 (8)\nAndroid: 16 (SDK 36)\nPhone RAM: 11.7 GB\nSoC: SM8750\n"));
        assertTrue(body.contains("\nGPU: Adreno (TM) 830, driver Turnip 25.1\n"));
        assertTrue(body.contains("\nCounter: 19200000 Hz\n"));
        assertTrue(body.contains("\nGuest: iOS 26, 4096 MiB RAM, screen 3x (1179x2556)\n"));
        assertTrue(body.contains("\nSetup: 1 h 15 min in total (Download 26 min, Build the disk 49 min)\n"));
        assertTrue(body.contains("\nCold boot: first frame of the live view 2 min 10 s after QEMU started\n"));
        assertTrue(body.contains("\nQuick-save load: first frame of the live view 22 s after QEMU started\n"));
        assertTrue(body.contains("\nLive view: 14.3 fps\n") || body.contains("\nLive view: 14.2 fps\n"));
        assertTrue(body.contains("\nvCPUs: halted 3%, el1 60%, el0-tcg 7%, el0-native 30%\n"));
        assertTrue(body.contains("\nThermal: 2 (moderate) at the highest during the run\n"));
        assertTrue(body.endsWith("\n\nAnything that does not work: "));
    }

    @Test
    public void whatIsNotKnownIsSaidSoAndNotMadeUp() {
        WorksText.In i = in();
        i.setup = null;
        i.run.coldS = -1;
        i.run.loadS = -1;
        i.run.fps = -1;
        i.run.halted = -1;
        i.run.thermalMax = -1;
        i.run.lowestAvailMb = -1;
        String body = WorksText.body(WorksText.facts(i));
        assertFalse(body.contains("Setup:"));
        assertFalse(body.contains("Cold boot"));
        assertTrue(body.contains("Live view: not measured"));
        assertTrue(body.contains("vCPUs: no sample"));
        assertTrue(body.contains("Thermal: unknown"));
        assertFalse(body.contains("MemAvailable"));
    }

    @Test
    public void ios16HasNoGpuLine() {
        WorksText.In i = in();
        i.guest = "ios16";
        i.gpu = "";
        i.driver = "";
        i.run.screen = "";
        i.run.ramMiB = 7168;
        String body = WorksText.body(WorksText.facts(i));
        assertTrue(body.contains("\nGPU: not used by this guest\n"));
        assertTrue(body.contains("\nGuest: iOS 16, 7168 MiB RAM\n"));
    }

    @Test
    public void bodyHoldsNoIdentifiers() {
        String body = WorksText.body(WorksText.facts(in())).toLowerCase(java.util.Locale.ROOT);
        for (String bad : new String[] {"serial", "android id", "ssaid", "/data/", "/storage/", "@", "http", "ip address"}) {
            assertFalse(bad, body.contains(bad));
        }
    }

    @Test
    public void aShortBodyIsKeptAsItIs() {
        String title = WorksText.title("Samsung SM-S938B", "SM8750", "Adreno (TM) 830", "ios26");
        List<String> f = WorksText.facts(in());
        assertEquals(WorksText.body(f), WorksText.fit(REPO, title, f, 4500));
    }

    @Test
    public void aLongBodyIsCutFromTheEndAndStaysUnderTheLimit() {
        WorksText.In i = in();
        // 40 steps with long names: the setup line is cut to its limit, the other facts stay
        SetupTimes t = new SetupTimes(0);
        for (int k = 0; k < 40; k++) {
            t.step("A long step name number " + k + " with words", k * 1000L);
        }
        t.finish(50_000);
        i.setup = SetupTimes.fromJson(t.toJson());
        String title = WorksText.title("Samsung SM-S938B", "SM8750", "Adreno (TM) 830", "ios26");
        List<String> f = WorksText.facts(i);
        for (int k = 0; k < 40; k++) {
            f.add("Extra fact " + k + ": " + "x%y z&x%y z&x%y z&x%y z&x%y z&x%y z&x%y z&x%y z&");
        }
        String body = WorksText.fit(REPO, title, f, 4500);
        assertTrue(CrashText.issueUrl(REPO, title, body).length() <= 4500);
        // the first facts and the ends of the text are there, the last facts are gone
        assertTrue(body.startsWith(WorksText.INTRO + "\n\nApp: 0.1.6 (8)\nAndroid: 16 (SDK 36)\n"));
        assertTrue(body.contains("SoC: SM8750"));
        assertTrue(body.endsWith("\n\nAnything that does not work: "));
        assertFalse(body.contains("Extra fact 39"));
        // the title is the caller's and is in the link whole
        assertTrue(CrashText.issueUrl(REPO, title, body).contains("title=Works%3A%20Samsung%20SM-S938B%20"));
    }

    @Test
    public void aTinyLimitLeavesTheIntroAndTheClosingLine() {
        String body = WorksText.fit(REPO, "Works: X (Y): iOS 26", WorksText.facts(in()), 10);
        assertEquals(WorksText.body(java.util.Collections.<String>emptyList()), body);
    }

    @Test
    public void durations() {
        assertEquals("45 s", WorksText.dur(45));
        assertEquals("12 min 4 s", WorksText.dur(724));
        assertEquals("12 min", WorksText.dur(720));
        assertEquals("1 h 5 min", WorksText.dur(3900));
        assertEquals("2 h", WorksText.dur(7200));
    }

    @Test
    public void setupTimesCountFromTheRunStartAndMergeRepeatedSteps() {
        SetupTimes t = new SetupTimes(1000);
        assertEquals(-1, t.totalS());
        t.step("A", 5000);
        t.step("B", 8000);
        t.step("A", 9000);
        t.finish(12_000);
        assertEquals(11, t.totalS());
        // A: from the run's start 1000 to 8000 = 7 s, then 9000 to 12000 = 3 s; B: 1 s
        assertEquals(10, (int) t.stepsS().get("A"));
        assertEquals(1, (int) t.stepsS().get("B"));
        SetupTimes.Kept k = SetupTimes.fromJson(t.toJson());
        assertNotNull(k);
        assertEquals(11, k.totalS);
        assertEquals(2, k.stepsS.size());
        assertNull(SetupTimes.fromJson("{\"steps\":[]}"));
        assertNull(SetupTimes.fromJson("nope"));
    }
}
