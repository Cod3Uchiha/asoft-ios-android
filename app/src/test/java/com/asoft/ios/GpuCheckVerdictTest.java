package com.asoft.ios;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** The compatibility-mode rule of the GPU check, fed with the Turnip sections of real user logs (app 0.1.5). */
public class GpuCheckVerdictTest {
    /**
     * Adreno 640 (OnePlus 7T, SM8150) as the probe writes it once storageBuffer16BitAccess is not required: the
     * sparse lacks only, every test run, host import types 0x2 of the wanted 0x7 with 0 words wrong.
     */
    private static final List<String> ADRENO_640 = Arrays.asList(
            "lacks: sparseBinding",
            "lacks: sparseResidencyBuffer",
            "recommended, absent: storageBuffer16BitAccess",
            "lacks: a queue that binds sparse memory",
            "device: vkCreateDevice VkResult 0",
            "test: compute",
            "compute: 0 of 4096 words wrong",
            "test: render",
            "render: 0 of 4096 pixels wrong",
            "test: host memory",
            "host import: memory types 0x2 (wanted 0x7)",
            "host memory: 0 of 1048576 words the GPU wrote into the phone's own pages wrong",
            "missing: sparseBinding, sparseResidencyBuffer, a queue that binds sparse memory",
            "result: fail");

    /** Adreno 619 (Redmi 15, SM6375): no memory type for the host import, so the host memory test fails. */
    private static final List<String> ADRENO_619 = Arrays.asList(
            "lacks: sparseBinding",
            "lacks: sparseResidencyBuffer",
            "recommended, absent: storageBuffer16BitAccess",
            "lacks: a queue that binds sparse memory",
            "device: vkCreateDevice VkResult 0",
            "test: compute",
            "compute: 0 of 4096 words wrong",
            "test: render",
            "render: 0 of 4096 pixels wrong",
            "test: host memory",
            "host import: memory types 0 (wanted 0x3)",
            "error: no memory type both the resource and the host import take",
            "lacks: GPU access to the phone's own memory (host pointer import)",
            "missing: sparseBinding, sparseResidencyBuffer, a queue that binds sparse memory, GPU access to the phone's own memory (host pointer import)",
            "result: fail");

    @Test
    public void adreno640PassesInTheCompatibilityMode() {
        assertTrue(GpuCheck.fallbackPassOfLog(ADRENO_640));
    }

    @Test
    public void adreno640WithTheOldRequiredLackStillFails() {
        // the 0.1.5 log: storageBuffer16BitAccess was a required lack then
        java.util.ArrayList<String> old = new java.util.ArrayList<>(ADRENO_640);
        old.add(0, "lacks: storageBuffer16BitAccess");
        assertFalse(GpuCheck.fallbackPassOfLog(old));
    }

    @Test
    public void adreno619FailsForTheHostImport() {
        assertFalse(GpuCheck.fallbackPassOfLog(ADRENO_619));
    }

    @Test
    public void aProbeThatDidNotEndOrSkippedTheHostTestFails() {
        java.util.ArrayList<String> noEnd = new java.util.ArrayList<>(ADRENO_640);
        noEnd.removeIf(l -> l.startsWith("missing: "));
        assertFalse(GpuCheck.fallbackPassOfLog(noEnd));
        java.util.ArrayList<String> noHost = new java.util.ArrayList<>(ADRENO_640);
        noHost.remove("test: host memory");
        assertFalse(GpuCheck.fallbackPassOfLog(noHost));
    }
}
