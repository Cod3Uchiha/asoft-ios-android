package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class QuietTest {
    @Test
    public void aRuntimeExceptionIsLoggedOnceAndSwallowed() {
        List<String> log = new ArrayList<>();
        // DeadSystemRuntimeException is a RuntimeException; a plain one stands in for it off the device
        boolean ok = Quiet.run("notify", log::add, () -> {
            throw new RuntimeException("android.os.DeadSystemException: the system died");
        });
        assertFalse(ok);
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("notify failed: "));
        assertTrue(log.get(0).contains("DeadSystemException"));
    }

    @Test
    public void aFinishedCallLogsNothing() {
        List<String> log = new ArrayList<>();
        int[] n = {0};
        assertTrue(Quiet.run("x", log::add, () -> n[0]++));
        assertEquals(1, n[0]);
        assertTrue(log.isEmpty());
    }

    @Test
    public void aLogThatFailsToo() {
        assertFalse(Quiet.run("x", s -> {
            throw new IllegalStateException("no storage");
        }, () -> {
            throw new RuntimeException("dead");
        }));
    }
}
