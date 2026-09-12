package verwaltungsassistent.web.service;

import verwaltungsassistent.web.service.InfrastructureMetricsService.Gpu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the infrastructure metrics: nvidia-smi output parsing and the
 * graceful-unavailable behavior (no fake values).
 */
class InfrastructureMetricsServiceTest {

    @Test
    void parsesNvidiaCsvLine() {
        Gpu gpu = InfrastructureMetricsService.parseNvidia("12, 14055, 16384");
        assertNotNull(gpu);
        assertEquals(12.0, gpu.utilizationPercent());
        assertEquals(14055, gpu.vramUsedMb());
        assertEquals(16384, gpu.vramTotalMb());
        assertEquals(85.8, gpu.vramPercent(), 0.1);
    }

    @Test
    void parsesNvidiaWithDecimalUtilization() {
        Gpu gpu = InfrastructureMetricsService.parseNvidia("5.5, 12000, 24576");
        assertNotNull(gpu);
        assertEquals(5.5, gpu.utilizationPercent());
    }

    @Test
    void missingOrGarbageNvidiaOutput_isUnavailable() {
        assertNull(InfrastructureMetricsService.parseNvidia(null));
        assertNull(InfrastructureMetricsService.parseNvidia(""));
        assertNull(InfrastructureMetricsService.parseNvidia("keine gpu"));
        assertNull(InfrastructureMetricsService.parseNvidia("12, n/a, 16384"));
    }

    @Test
    void snapshot_alwaysProvidesCpuAndRam() {
        InfrastructureMetricsService service = new InfrastructureMetricsService();
        InfrastructureMetricsService.Metrics m = service.snapshot();
        assertNotNull(m.ram(), "RAM comes from the JVM OS bean and must be available");
        assertTrue(m.ram().totalMb() > 0);
        assertTrue(m.ram().usedMb() >= 0);
        // GPU may or may not be available in the test environment; when it is,
        // the values must be plausible.
        if (m.gpuAvailable()) {
            assertTrue(m.gpu().vramTotalMb() > 0);
            assertTrue(m.gpu().utilizationPercent() >= 0);
        }
    }

    @Test
    void vramHistory_respectsSamplingIntervalAndWindow() throws Exception {
        InfrastructureMetricsService service = new InfrastructureMetricsService();
        // Two snapshots within the 30s sampling interval only record one sample.
        service.snapshot();
        service.snapshot();
        Thread.sleep(50);
        service.snapshot();
        assertTrue(service.vramHistory().size() <= 1,
                "VRAM samples must respect the 30s sampling interval");
    }

    @Test
    void gpuHistory_recordsUtilizationSamplesAlongsideVram() throws Exception {
        InfrastructureMetricsService service = new InfrastructureMetricsService();
        // Drive the sampling directly (nvidia-smi is not required for the
        // history logic); the interval guard is reset between calls so both
        // samples are recorded.
        var record = InfrastructureMetricsService.class.getDeclaredMethod("recordSamples", Gpu.class);
        record.setAccessible(true);
        var lastField = InfrastructureMetricsService.class.getDeclaredField("lastSampleAt");
        lastField.setAccessible(true);

        record.invoke(service, new Gpu(12.0, 14055, 16384));
        lastField.set(service, null);
        record.invoke(service, new Gpu(27.5, 14100, 16384));

        var history = service.gpuHistory();
        assertEquals(2, history.size(), "both GPU samples must be recorded");
        assertEquals(12.0, history.get(0).utilizationPercent());
        assertEquals(27.5, history.get(1).utilizationPercent());
        assertTrue(history.get(1).timestampMs() >= history.get(0).timestampMs(),
                "history must be ordered oldest first");
        assertEquals(2, service.vramHistory().size(),
                "VRAM history is recorded from the same samples");
    }
}
