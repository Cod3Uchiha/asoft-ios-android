package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SetupStateTest {
    private static final List<String> KEYS = Arrays.asList("shared:fetch", "shared:build", "ios26:fetch", "ios26:pack");

    @Test
    public void placeFollowsTheCurrentStep() {
        assertEquals(3, SetupState.place(KEYS, k -> false, "ios26:fetch"));
    }

    @Test
    public void placeIsTheFirstStepNotDoneBetweenSteps() {
        Set<String> done = new HashSet<>(Arrays.asList("shared:fetch", "shared:build"));
        assertEquals(3, SetupState.place(KEYS, done::contains, ""));
        // all done: the last one
        assertEquals(4, SetupState.place(KEYS, k -> true, ""));
        assertEquals(0, SetupState.place(Arrays.asList(), k -> true, ""));
    }

    @Test
    public void progressText() {
        assertEquals("step 2 of 12, 42%", SetupState.progress(2, 12, 42));
        assertEquals("step 2 of 12", SetupState.progress(2, 12, -1));
    }

    @Test
    public void planProgressAndWaiting() {
        SetupState.Plan p = new SetupState.Plan();
        p.keys.addAll(KEYS);
        p.current = "shared:build";
        assertEquals("step 2 of 4, 7%", SetupState.progress(p, 7));
        p.waitsFor = "watchOS";
        assertEquals("waits for watchOS (step 1 of 4)", SetupState.progress(p, 7));
        assertNull(SetupState.progress((SetupState.Plan) null, 0));
        assertNull(SetupState.progress(new SetupState.Plan(), 0));
    }

    @Test
    public void stateLines() {
        assertEquals("Not set up", SetupState.line(false, false, false, null, "", ""));
        assertEquals("Setting up: step 3 of 12, 42%", SetupState.line(false, true, false, "step 3 of 12, 42%", "", ""));
        assertEquals("Paused: step 3 of 12", SetupState.line(false, true, true, "step 3 of 12", "", ""));
        assertEquals("Set up, 6.4 GB, 12 Nov", SetupState.line(true, false, false, null, "6.4 GB", "12 Nov"));
        assertEquals("Set up", SetupState.line(true, false, false, null, "", ""));
        assertEquals("Stopped at step 4 of 6", SetupState.line(false, false, false, "step 4 of 6", "", ""));
    }

    @Test
    public void sizes() {
        assertEquals("12.3 GB", SetupState.size(12_300_000_000L));
        assertEquals("450 MB", SetupState.size(450_000_000L));
        assertEquals("", SetupState.day(0));
    }
}
