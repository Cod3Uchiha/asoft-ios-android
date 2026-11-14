package com.asoft.ios;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** The phone's counter frequency (A#66): what it is passed to, and that a 19.2 MHz phone gets exactly what it got before. */
public class CounterFreqTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void reset() {
        CounterFreq.setForTest(0);
    }

    // -- a device tree like Apple's: [nprops][nchildren] then props (32-byte name, u32 length, value padded to 4), children

    private static void prop(ByteArrayOutputStream o, String name, byte[] v) {
        ByteBuffer b = ByteBuffer.allocate(36 + ((v.length + 3) & ~3)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        b.put(n);
        b.position(32);
        b.putInt(v.length);
        b.put(v);
        o.write(b.array(), 0, b.capacity());
    }

    private static byte[] u64(long v) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
    }

    private static byte[] str(String s) {
        return (s + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static void head(ByteArrayOutputStream o, int props, int children) {
        byte[] h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(props).putInt(children).array();
        o.write(h, 0, 8);
    }

    private static void cpu(ByteArrayOutputStream o, int n, long hz) {
        head(o, 3, 0);
        prop(o, "name", str("cpu" + n));
        prop(o, "reg", new byte[] {(byte) n, 0, 0, 0});
        prop(o, "timebase-frequency", u64(hz));
    }

    /** device-tree { chosen {name, timebase-frequency (a decoy outside /cpus)}, cpus { cpu0..cpuN-1 } } */
    static byte[] tree(int cpus, long hz) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        head(o, 1, 2);
        prop(o, "name", str("device-tree"));
        head(o, 2, 0);
        prop(o, "name", str("chosen"));
        prop(o, "timebase-frequency", u64(24_000_000L));
        head(o, 2, cpus);
        prop(o, "name", str("cpus"));
        prop(o, "#size-cells", new byte[] {0, 0, 0, 0});
        for (int i = 0; i < cpus; i++) {
            cpu(o, i, hz);
        }
        return o.toByteArray();
    }

    @Test
    public void everyCpuIsPatchedAndNothingElse() throws IOException {
        byte[] t = tree(4, 19_200_000L);
        assertEquals(4, CounterFreq.timebaseOffsets(t).size());
        byte[] p = CounterFreq.withTimebase(t, 1_000_000_000L);
        assertEquals(t.length, p.length);
        java.util.List<Integer> at = CounterFreq.timebaseOffsets(p);
        for (int o : at) {
            assertEquals(1_000_000_000L, ByteBuffer.wrap(p, o, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
        }
        // every byte that differs lies inside one of the four values: the decoy in /chosen (24 MHz) and the layout are as they were
        for (int i = 0; i < t.length; i++) {
            boolean inside = false;
            for (int o : at) {
                inside |= i >= o && i < o + 8;
            }
            assertTrue("byte " + i, t[i] == p[i] || inside);
        }
    }

    @Test
    public void thereAndBackIsTheSameBytes() throws IOException {
        byte[] t = tree(4, 19_200_000L);
        byte[] back = CounterFreq.withTimebase(CounterFreq.withTimebase(t, 1_000_000_000L), 19_200_000L);
        assertArrayEquals(t, back);
    }

    @Test
    public void aTreeWithTheRightFrequencyIsNeverPatched() throws IOException {
        byte[] t = tree(4, 19_200_000L);
        assertSame(t, CounterFreq.withTimebase(t, 19_200_000L));
        File dir = tmp.newFolder("files");
        File src = tmp.newFile("dtree-root26v-smp4-19m2");
        Files.write(src.toPath(), t);
        long mtime = src.lastModified();
        assertEquals(src, CounterFreq.fit(dir, src, 19_200_000L));
        assertEquals(0, dir.list().length);                          // nothing written
        assertArrayEquals(t, Files.readAllBytes(src.toPath()));      // the tree itself untouched
        assertEquals(mtime, src.lastModified());
    }

    @Test
    public void aTreeWithAnotherFrequencyGetsACopy() throws IOException {
        byte[] t = tree(4, 19_200_000L);
        File dir = tmp.newFolder("files");
        File src = tmp.newFile("dtree-root26v-smp4-19m2");
        Files.write(src.toPath(), t);
        File out = CounterFreq.fit(dir, src, 1_000_000_000L);
        assertEquals(new File(dir, "dtree-root26v-smp4-19m2-tb1000000000"), out);
        assertArrayEquals(CounterFreq.withTimebase(t, 1_000_000_000L), Files.readAllBytes(out.toPath()));
        assertArrayEquals(t, Files.readAllBytes(src.toPath()));      // the original keeps its name and bytes
        // asked again: the copy is used as it is
        long m = out.lastModified();
        assertEquals(out, CounterFreq.fit(dir, src, 1_000_000_000L));
        assertEquals(m, out.lastModified());
        assertFalse(new File(dir, out.getName() + ".tmp").exists());
    }

    @Test
    public void aTreeThatIsNotATreeIsRefused() throws IOException {
        try {
            CounterFreq.withTimebase(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, 1_000_000_000L);
            fail();
        } catch (IOException expected) {
            // as dt-phone.py exits
        }
        byte[] noCpus = new byte[0];
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        head(o, 1, 0);
        prop(o, "name", str("device-tree"));
        noCpus = o.toByteArray();
        try {
            CounterFreq.withTimebase(noCpus, 1_000_000_000L);
            fail();
        } catch (IOException expected) {
            // no /cpus/*/timebase-frequency
        }
    }

    /** A real tree (the one the setup's dt-phone.py patched), when a developer points ASOFT_TEST_DTREE at one: byte-equal to that script. */
    @Test
    public void aRealTreeLikeTheScript() throws IOException {
        String p = System.getenv("ASOFT_TEST_DTREE");
        Assume.assumeTrue(p != null && new File(p).isFile());
        String want = System.getenv("ASOFT_TEST_DTREE_PATCHED");    // dt-phone.py --timebase 1000000000 on it
        byte[] t = Files.readAllBytes(new File(p).toPath());
        assertTrue(CounterFreq.timebaseOffsets(t).size() >= 4);
        if (want != null) {
            assertArrayEquals(Files.readAllBytes(new File(want).toPath()), CounterFreq.withTimebase(t, 1_000_000_000L));
        }
    }

    // -- the value --------------------------------------------------------------------------------------------

    @Test
    public void nonsenseFallsBackToTheOldConstant() {
        assertEquals(0, CounterFreq.sane(0));
        assertEquals(0, CounterFreq.sane(-1));
        assertEquals(0, CounterFreq.sane(999_999));
        assertEquals(0, CounterFreq.sane(0x1_0000_0000L));
        assertEquals(19_200_000L, CounterFreq.sane(19_200_000L));
        assertEquals(1_000_000_000L, CounterFreq.sane(1_000_000_000L));
        assertEquals(24_000_000L, CounterFreq.sane(24_000_000L));
        // no native library in a JVM test: the read fails and says 19.2 MHz
        CounterFreq.setForTest(0);
        assertEquals(19_200_000L, CounterFreq.hz());
    }

    @Test
    public void theReportsCarryTheCounter() {
        CounterFreq.setForTest(19_200_000L);
        assertEquals("counter: 19200000 Hz", CounterFreq.text());
        CounterFreq.setForTest(1_000_000_000L);
        assertEquals("counter: 1000000000 Hz (not 19.2 MHz)", CounterFreq.text());
    }

    // -- a 19.2 MHz phone gets what rc-0.1.6 gave it -----------------------------------------------------------------

    @Test
    public void helperCommandLineIsTheOldOneOnA19MhzPhone() {
        CounterFreq.setForTest(19_200_000L);
        assertEquals("cortex-a76,x-host-counter=on,cntfrq=19200000", Helper.cpuArg(true, Helper.hostCounterHz()));
        assertEquals("cortex-a76", Helper.cpuArg(false, Helper.hostCounterHz()));
        CounterFreq.setForTest(1_000_000_000L);
        assertEquals("cortex-a76,x-host-counter=on,cntfrq=1000000000", Helper.cpuArg(true, Helper.hostCounterHz()));
    }

    @Test
    public void iosAndWatchCpuLinesAreUntouched() {
        // the engine takes the guest's counter frequency from the tree (vresearch.c), so no cntfrq= here, as before
        assertEquals("max,pauth-impdef=on,x-el0-pac-off=on,sve=off,sme=off,x-host-counter=on,x-keep-kernel-tlb=off", Guest.CPU26);
        assertEquals(Guest.CPU26, WatchProfile.CPU);
        assertEquals("dtree-root26v-smp4-19m2", Guest.DTREE26);
        assertEquals("dtree-w-3g-19m2", WatchProfile.DTREE);
    }

    private static String job(String name) throws IOException {
        File f = new File("../setup/phone/jobs/" + name + ".sh");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void jobScriptsGetTheOldTextOnA19MhzPhone() throws IOException {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("TIMEBASE", String.valueOf(19_200_000L));
        String shared = PhoneSetup26.fill(job("shared-build"), v);
        String watch = PhoneSetup26.fill(job("watch-prep"), v);
        assertTrue(shared.contains("python3 -I $S/dt-phone.py dtree-smp4 dtree-phone --timebase 19200000\n"));
        assertTrue(watch.contains("python3 -I $S/dt-phone.py watchos/dtree-w watchos/dtree-w-3g-19m2 --ram-gib 3 --timebase 19200000\n"));
        assertFalse(shared.contains("{TIMEBASE}") || watch.contains("{TIMEBASE}"));
        // and on another phone only that number changes
        v.put("TIMEBASE", "1000000000");
        assertEquals(shared.replace("--timebase 19200000", "--timebase 1000000000"), PhoneSetup26.fill(job("shared-build"), v));
        assertEquals(watch.replace("--timebase 19200000", "--timebase 1000000000"), PhoneSetup26.fill(job("watch-prep"), v));
        assertNotEquals(shared, PhoneSetup26.fill(job("shared-build"), v));
    }

    // -- quick saves ------------------------------------------------------------------------------------------------

    @Test
    public void aSaveOn19MhzKeepsItsOldNoteAndLoads() {
        assertEquals("host-counter-in-state", CounterFreq.clockNote("host-counter-in-state", 19_200_000L));
        assertEquals("watch-host-counter-19m2", CounterFreq.clockNote("watch-host-counter-19m2", 19_200_000L));
        // every save so far: no "@", "host-counter-in-state\n" or none at all (null: a save with no .clock file)
        assertEquals(19_200_000L, CounterFreq.noteHz("host-counter-in-state"));
        assertEquals(19_200_000L, CounterFreq.noteHz("host-counter-in-state\n"));
        assertEquals(19_200_000L, CounterFreq.noteHz("watch-host-counter-19m2"));
        assertEquals(19_200_000L, CounterFreq.noteHz(null));
    }

    @Test
    public void aSaveOnAnotherCounterDoesNotMatch() {
        String note = CounterFreq.clockNote("host-counter-in-state", 1_000_000_000L);
        assertEquals("host-counter-in-state@1000000000", note);
        assertEquals(1_000_000_000L, CounterFreq.noteHz(note + "\n"));
        assertNotEquals(19_200_000L, CounterFreq.noteHz(note));       // a 19.2 MHz phone will not load it
        assertNotEquals(1_000_000_000L, CounterFreq.noteHz("host-counter-in-state"));    // nor a 1 GHz phone an old one
        assertEquals(-1, CounterFreq.noteHz("host-counter-in-state@junk"));
        assertEquals("watch-host-counter-19m2@1000000000", CounterFreq.clockNote("watch-host-counter-19m2", 1_000_000_000L));
    }
}
