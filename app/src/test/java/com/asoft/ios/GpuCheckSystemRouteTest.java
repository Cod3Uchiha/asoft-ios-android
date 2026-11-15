package com.asoft.ios;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** The experimental pass on the phone's own Vulkan driver (GpuCheck.systemPassOfLog), from logs in gpucheck.c's format. */
public class GpuCheckSystemRouteTest {
    private static final String NOT_COHERENT = GpuCheck.HOST_NOT_COHERENT + " (flags 0xb)";

    /** A Mali without sparse binding whose only host import is cached and not coherent; it has rasterization order access. */
    private static final List<String> MALI = Arrays.asList(
            "loader: libvulkan.so",
            "gpu: Mali-G1-Ultra", "device api: Vulkan 1.3.0",
            "lacks: sparseBinding", "lacks: sparseResidencyBuffer", "lacks: a queue that binds sparse memory",
            "recommended, absent: VK_EXT_fragment_shader_interlock",
            "device: vkCreateDevice VkResult 0",
            "test: compute", "compute: 0 of 4096 words wrong", "test: render", "render: 0 of 4096 pixels wrong",
            "test: host memory", "host import: memory types 0x2 (wanted 0x7)", "host import: memory type 1, flags 0xb",
            NOT_COHERENT,
            "host memory: 0 of 1048576 words the GPU wrote into the phone's own pages wrong",
            "missing: sparseBinding, sparseResidencyBuffer, a queue that binds sparse memory",
            "result: fail");

    /** A driver that passes everything, sparse binds included. */
    private static final List<String> CLEAN = Arrays.asList(
            "loader: libvulkan.so",
            "gpu: Some GPU", "device api: Vulkan 1.3.0", "device: vkCreateDevice VkResult 0",
            "test: compute", "compute: 0 of 4096 words wrong", "test: render", "render: 0 of 4096 pixels wrong",
            "test: host memory", "host import: memory types 0x2 (wanted 0x7)", "host import: memory type 1, flags 0xf",
            "host memory: 0 of 1048576 words the GPU wrote into the phone's own pages wrong",
            "test: sparse gather", "sparse gather: 0 of 256 pieces wrong",
            "result: pass");

    private static List<String> copy(List<String> l) {
        return new ArrayList<>(l);
    }

    @Test
    public void maliWithoutSparseAndANonCoherentImportPasses() {
        assertTrue(GpuCheck.systemPassOfLog(MALI));
        assertTrue(GpuCheck.hostCacheLack(MALI) != null);
    }

    @Test
    public void aCleanPassPasses() {
        assertTrue(GpuCheck.systemPassOfLog(CLEAN));
    }

    @Test
    public void aHostMemoryLackFails() {
        List<String> log = copy(MALI);
        log.add(log.indexOf("missing: sparseBinding, sparseResidencyBuffer, a queue that binds sparse memory"),
                "lacks: GPU access to the phone's own memory (host pointer import)");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void noFramebufferFetchFails() {
        List<String> log = copy(MALI);
        log.add(log.indexOf("device: vkCreateDevice VkResult 0"), "recommended, absent: VK_EXT_rasterization_order_attachment_access");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void oneFramebufferFetchExtensionIsEnough() {
        // MALI lacks only the interlock; a driver with only the interlock passes too
        List<String> log = copy(MALI);
        log.remove("recommended, absent: VK_EXT_fragment_shader_interlock");
        log.add(log.indexOf("device: vkCreateDevice VkResult 0"), "recommended, absent: VK_EXT_rasterization_order_attachment_access");
        assertTrue(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void aCrashedProbeFails() {
        List<String> log = copy(MALI.subList(0, MALI.indexOf("test: render")));
        log.add("error: the probe's process ended without a result (the driver crashed it)");
        log.add("result: fail");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void aTimedOutProbeFails() {
        List<String> log = copy(MALI.subList(0, MALI.indexOf("test: render")));
        log.add("error: no result in 90 s (the driver hangs)");
        log.add("result: fail");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void theTimeoutLineAloneStopsAnOtherwiseCompleteLog() {
        List<String> log = copy(CLEAN);
        log.add(log.size() - 1, "error: no result in 90 s (the driver hangs)");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void aLackOutsideTheCoveredOnesFails() {
        List<String> log = copy(MALI);
        log.add(log.indexOf("test: compute"), "lacks: shaderInt16");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void wrongComputeFails() {
        List<String> log = copy(MALI);
        log.set(log.indexOf("compute: 0 of 4096 words wrong"), "compute: 17 of 4096 words wrong");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void wrongRenderFails() {
        List<String> log = copy(MALI);
        log.set(log.indexOf("render: 0 of 4096 pixels wrong"), "render: 4096 of 4096 pixels wrong");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void aMissingTestOrDeviceFails() {
        List<String> noHost = copy(MALI);
        noHost.remove("test: host memory");
        assertFalse(GpuCheck.systemPassOfLog(noHost));
        List<String> noDevice = copy(MALI);
        noDevice.remove("device: vkCreateDevice VkResult 0");
        assertFalse(GpuCheck.systemPassOfLog(noDevice));
        List<String> noResults = copy(MALI);
        noResults.removeIf(l -> l.startsWith("compute: "));
        assertFalse(GpuCheck.systemPassOfLog(noResults));
    }

    @Test
    public void aLogThatNeverEndedFails() {
        List<String> log = copy(MALI);
        log.removeIf(l -> l.startsWith("missing: ") || l.startsWith("result: "));
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void theAdrenoSystemLogWithoutTheHostImportFails() {
        // an Adreno's own driver (not Turnip): no VK_EXT_external_memory_host, so the host test never runs
        List<String> log = Arrays.asList(
                "loader: libvulkan.so",
                "gpu: Adreno (TM) 740", "device api: Vulkan 1.3.0",
                "lacks: VK_EXT_external_memory_host",
                "device: vkCreateDevice VkResult 0",
                "test: compute", "compute: 0 of 4096 words wrong", "test: render", "render: 0 of 4096 pixels wrong",
                "missing: VK_EXT_external_memory_host",
                "result: fail");
        assertFalse(GpuCheck.systemPassOfLog(log));
    }

    @Test
    public void anEmptyLogFails() {
        assertFalse(GpuCheck.systemPassOfLog(new ArrayList<>()));
    }
}
