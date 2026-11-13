package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SetupTickTest {
    private File dir;

    @Before
    public void dir() throws IOException {
        dir = Files.createTempDirectory("setuptick").toFile();
    }

    @After
    public void clean() {
        File[] f = dir.listFiles();
        if (f != null) {
            for (File x : f) {
                x.delete();
            }
        }
        dir.delete();
    }

    private static String amfi(int pid) {
        return "AMFI: Launch Constraint Violation (not enforcing), error info: c[6]p[2]m[3]e[255], (Process was launched as a system"
                + " service unexpectedly) launching proc[vc: 1 pid: " + pid + "]: /bin/bash, launch type 1, failure proc [vc: 1 pid: 1]\n";
    }

    private void append(File f, String s) throws IOException {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.ISO_8859_1), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    public void theConsoleScanCountsWhatK18Asks() {
        String t = amfi(65342) + "\"AppleSEPKeyStore\":pid:61,sel:142:2979: SEP is not booted\n"
                + "IOMFB: default_fb_surface: No Default FB Surface found. Return error\n" + amfi(65471)
                + "Trying to restart GPU (Undefined)...\n";
        SetupTick.Console c = SetupTick.scanConsole(t, 1200);
        assertEquals(65471, c.maxPid);
        assertEquals(2, c.bash);
        assertEquals(1, c.displayStarts);
        assertEquals(1, c.gpuRestarts);
        assertEquals(t.indexOf("pid: 65342"), c.jumpAt);
    }

    @Test
    public void aHealthyBootHasNoJump() {
        SetupTick.Console c = SetupTick.scanConsole(amfi(46) + "\"AppleSEPKeyStore\":pid:0,:2979: x\n" + amfi(1246), 0);
        assertEquals(1246, c.maxPid);
        assertEquals(-1, c.jumpAt);
    }

    @Test
    public void aStepOfExactlyTheJumpIsNoJump() {
        assertEquals(-1, SetupTick.scanConsole(amfi(3200), 1200).jumpAt);
        assertTrue(SetupTick.scanConsole(amfi(3201), 1200).jumpAt >= 0);
    }

    @Test
    public void stderrCountsAndTheSamplersClock() {
        String e = "reims[warn] x: task 2: exec faulted: resource 48 not found\n"
                + "reims[warn] reims_vgpu_core::device::waits: waiter 4 waited 2s for stamp 1 to reach 0x1fd1000\n"
                + "el-sample t=11130s waits: qemu\nel-sample t=11633s cpu0\n";
        assertEquals(1, SetupTick.count(java.util.regex.Pattern.compile("exec faulted"), e));
        assertEquals(11633, SetupTick.samplerT(e, -1));
        assertEquals(77, SetupTick.samplerT("nothing", 77));
    }

    @Test
    public void aTickLineReportsTheNewTextOnly() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        append(serial, amfi(46));
        append(err, "el-sample t=30s a\n");
        SetupTick t = new SetupTick(serial, err, new File(dir, "logs"), "wallpaper", 5);
        String first = t.tick(0, "5 min", 9, 0, 31);
        assertTrue(first, first.contains("console pid max 46,"));
        assertTrue(first, first.contains("bash launches 1,"));
        assertTrue(first, first.contains("frames +4,"));
        assertTrue(first, first.contains("sampler t=30 s of 31 s"));
        append(serial, amfi(47) + amfi(48));
        String second = t.tick(5, "5 min", 9, 12, 331);
        assertTrue(second, second.contains("bash launches 2,"));
        assertTrue(second, second.contains("frames +0,"));
        assertTrue(second, second.contains("phone asleep 12 s"));
        assertTrue(second, second.contains("sampler t=30 s of 331 s"));     // no new sample: the last one stays
        assertFalse(new File(dir, "logs").exists());
    }

    @Test
    public void theFirstPidJumpKeeps64KbBeforeAndAfter() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        File logs = new File(dir, "logs");
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            pad.append("hfs: set hardlink bit on vol=iOSRoot cnid=450708 inoid=").append(i).append('\n');
        }
        append(serial, amfi(46));
        SetupTick t = new SetupTick(serial, err, logs, "wallpaper", 0);
        t.tick(0, "5 min", 0, 0, 0);
        append(serial, pad.toString());                         // ~190 KB before the jump
        append(serial, amfi(65342) + pad);                       // and the same after it
        t.tick(5, "5 min", 0, 0, 300);
        File before = new File(logs, "wallpaper-serial-pidjump-before.txt");
        File after = new File(logs, "wallpaper-serial-pidjump-after.txt");
        assertEquals(SetupTick.SLICE, before.length());
        assertEquals(SetupTick.SLICE, after.length());
        assertTrue(new String(Files.readAllBytes(after.toPath()), StandardCharsets.ISO_8859_1).startsWith("pid: 65342"));
        // a second jump keeps nothing new
        before.delete();
        append(serial, amfi(130000));
        t.tick(10, "5 min", 0, 0, 600);
        assertFalse(before.exists());
    }

    @Test
    public void aJumpWithLittleAfterItIsCompletedAtTheNextTick() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        File logs = new File(dir, "logs");
        SetupTick t = new SetupTick(serial, err, logs, "wallpaper", 0);
        append(serial, "early line\n" + amfi(70000) + "after one\n");
        t.tick(5, "5 min", 0, 0, 300);
        File after = new File(logs, "wallpaper-serial-pidjump-after.txt");
        assertFalse(after.exists());
        append(serial, "after two\n");
        t.tick(10, "5 min", 0, 0, 600);
        String s = new String(Files.readAllBytes(after.toPath()), StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("pid: 70000"));
        assertTrue(s.endsWith("after one\nafter two\n"));
        assertTrue(new String(Files.readAllBytes(new File(logs, "wallpaper-serial-pidjump-before.txt").toPath()),
                StandardCharsets.ISO_8859_1).startsWith("early line\n"));
    }

    @Test
    public void theFirstNoAnswerKeepsTheConsoleBeforeItAndWhatFollowsAtTheNextTick() throws IOException {
        File serial = new File(dir, "serial.txt"), err = new File(dir, "qemu-stderr.txt");
        File logs = new File(dir, "logs");
        SetupTick t = new SetupTick(serial, err, logs, "wallpaper", 0);
        append(serial, "before one\n");
        t.silent();
        t.silent();                                             // only the first counts
        append(serial, "after one\n");
        t.tick(5, "5 min", 0, 0, 300);
        assertEquals("before one\n", new String(Files.readAllBytes(new File(logs, "wallpaper-serial-noanswer-before.txt").toPath()),
                StandardCharsets.ISO_8859_1));
        assertEquals("after one\n", new String(Files.readAllBytes(new File(logs, "wallpaper-serial-noanswer-after.txt").toPath()),
                StandardCharsets.ISO_8859_1));
    }

    @Test
    public void aMissingFileIsNoFailure() {
        SetupTick t = new SetupTick(new File(dir, "none"), new File(dir, "none2"), new File(dir, "logs"), "wallpaper", 0);
        assertTrue(t.tick(0, "5 min", 0, 0, 0).contains("console pid max 0,"));
    }
}
