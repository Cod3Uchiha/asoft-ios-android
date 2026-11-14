package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HoldRuleTest {
    private static final long MIN = 60_000L;

    @Test
    public void moderateNeverHolds() {
        HoldRule r = new HoldRule(true);
        for (long t = 0; t < 120 * MIN; t += 500) {
            assertFalse(r.decide(2, false, t).holding);
        }
    }

    @Test
    public void severeHoldsAndModerateReleases() {
        // A#63: before 0.1.6 a hold at severe lasted until "light"; a phone that settles at moderate never went on
        HoldRule r = new HoldRule(true);
        assertTrue(r.decide(3, false, 0).holding);
        assertTrue(r.decide(3, false, 5 * MIN).holding);
        assertTrue(r.decide(3, false, 6 * MIN).holding);
        assertFalse(r.decide(2, false, 6 * MIN + 500).holding);
        assertFalse(r.decide(2, false, 60 * MIN).holding);
    }

    @Test
    public void boundedSevereHoldRunsForTwoMinutesThenHoldsAgain() {
        HoldRule r = new HoldRule(true);
        assertTrue(r.decide(3, false, 0).holding);
        assertTrue(r.decide(3, false, HoldRule.SEVERE_HOLD_MS - 1).holding);
        assertFalse(r.decide(3, false, HoldRule.SEVERE_HOLD_MS).holding);
        long t = HoldRule.SEVERE_HOLD_MS;
        assertFalse(r.decide(3, false, t + HoldRule.SEVERE_RUN_MS - 1).holding);
        assertTrue(r.decide(3, false, t + HoldRule.SEVERE_RUN_MS).holding);
        // and again 10 minutes later
        assertTrue(r.decide(3, false, t + HoldRule.SEVERE_RUN_MS + HoldRule.SEVERE_HOLD_MS - 1).holding);
        assertFalse(r.decide(3, false, t + HoldRule.SEVERE_RUN_MS + HoldRule.SEVERE_HOLD_MS).holding);
    }

    @Test
    public void unboundedGateHoldsAtSevereUntilModerate() {
        HoldRule r = new HoldRule(false);
        assertTrue(r.decide(3, false, 0).holding);
        assertTrue(r.decide(3, false, 3 * 60 * MIN).holding);
        assertFalse(r.decide(2, false, 3 * 60 * MIN + 500).holding);
    }

    @Test
    public void criticalHoldsWithoutLimit() {
        HoldRule r = new HoldRule(true);
        assertTrue(r.decide(4, false, 0).holding);
        assertTrue(r.decide(4, false, 60 * MIN).holding);
        assertTrue(r.decide(5, false, 61 * MIN).holding);
        assertTrue(r.decide(6, false, 62 * MIN).holding);
        assertFalse(r.decide(2, false, 63 * MIN).holding);
    }

    @Test
    public void criticalDuringARunWindowHoldsAtOnce() {
        HoldRule r = new HoldRule(true);
        r.decide(3, false, 0);
        assertFalse(r.decide(3, false, HoldRule.SEVERE_HOLD_MS).holding);
        assertTrue(r.decide(4, false, HoldRule.SEVERE_HOLD_MS + 1000).holding);
    }

    @Test
    public void aFlickerAroundSevereDoesNotStopAndStartTwiceASecond() {
        HoldRule r = new HoldRule(true);
        assertTrue(r.decide(3, false, 0).holding);
        assertFalse(r.decide(2, false, 1000).holding);
        // back at severe 1 s later: not held again until the release is 30 s old
        assertFalse(r.decide(3, false, 2000).holding);
        assertFalse(r.decide(3, false, 1000 + HoldRule.MIN_RUN_MS - 1).holding);
        assertTrue(r.decide(3, false, 1000 + HoldRule.MIN_RUN_MS).holding);
    }

    @Test
    public void lowBatteryHoldsUntilTheCharger() {
        HoldRule r = new HoldRule(true);
        HoldRule.Hold h = r.decide(1, true, 0);
        assertTrue(h.holding);
        assertEquals(HoldRule.Why.BATTERY, h.why);
        assertTrue(h.note().contains("charger"));
        assertFalse(r.decide(1, false, 500).holding);
    }

    @Test
    public void heatIsTheReasonWhenBothApply() {
        HoldRule r = new HoldRule(true);
        assertEquals(HoldRule.Why.HOT, r.decide(3, true, 0).why);
    }

    @Test
    public void theNoteNamesTheStatusAndIsNullWhenNotHeld() {
        HoldRule r = new HoldRule(true);
        HoldRule.Hold h = r.decide(3, false, 0);
        assertNotNull(h.note());
        assertTrue(h.note().contains("severe"));
        assertTrue(h.log().contains("thermal 3"));
        assertNull(r.decide(1, false, 1000).note());
    }

    @Test
    public void thermalNames() {
        assertEquals("moderate", HoldRule.thermalName(2));
        assertEquals("shutdown", HoldRule.thermalName(6));
        assertEquals("unknown", HoldRule.thermalName(-1));
    }
}
