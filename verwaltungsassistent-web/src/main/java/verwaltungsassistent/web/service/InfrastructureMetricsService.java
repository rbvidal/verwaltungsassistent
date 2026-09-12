package verwaltungsassistent.web.service;

import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * System resource monitoring for Administration → Infrastruktur. CPU and RAM
 * come from the JVM's operating-system bean; GPU/VRAM come from
 * {@code nvidia-smi} when the runtime provides it. When no GPU metrics can be
 * obtained the values are simply unavailable — nothing is faked.
 *
 * <p>A small application-side rolling history (last ~30 minutes, sampled on
 * every metrics poll) backs the VRAM graph; only actually collected samples
 * are stored.</p>
 */
@Service
public class InfrastructureMetricsService {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureMetricsService.class);
    private static final long HISTORY_WINDOW_SECONDS = 30 * 60;
    private static final long SAMPLE_INTERVAL_SECONDS = 30;

    private final Deque<VramSample> vramHistory = new ArrayDeque<>();
    private final Deque<GpuSample> gpuHistory = new ArrayDeque<>();
    private volatile Instant lastSampleAt;

    /** Current resource snapshot. Nullable GPU values mean "not available". */
    public Metrics snapshot() {
        Metrics m = new Metrics(cpuPercent(), ram(), gpu());
        recordSamples(m.gpu());
        return m;
    }

    /** The collected VRAM history (newest last), capped at the rolling window. */
    public List<VramSample> vramHistory() {
        synchronized (vramHistory) {
            return new ArrayList<>(vramHistory);
        }
    }

    /** The collected GPU utilization history (newest last), capped at the rolling window. */
    public List<GpuSample> gpuHistory() {
        synchronized (gpuHistory) {
            return new ArrayList<>(gpuHistory);
        }
    }

    private Double cpuPercent() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double cpu = os.getCpuLoad();
            if (cpu >= 0) return Math.round(cpu * 1000) / 10.0;
            double load = os.getSystemLoadAverage();
            if (load > 0) return Math.round(load * 100) / 100.0;
        } catch (Exception e) {
            log.debug("CPU metric unavailable: {}", e.getMessage());
        }
        return null;
    }

    private Ram ram() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalPhysicalMemorySize();
            long free = os.getFreePhysicalMemorySize();
            if (total <= 0) return null;
            return new Ram(total / (1024 * 1024), Math.max(0, (total - free)) / (1024 * 1024));
        } catch (Exception e) {
            log.debug("RAM metric unavailable: {}", e.getMessage());
            return null;
        }
    }

    private Gpu gpu() {
        try {
            Process process = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=utilization.gpu,memory.used,memory.total",
                    "--format=csv,noheader,nounits")
                    .redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return parseNvidia(out);
        } catch (IOException | InterruptedException e) {
            log.debug("GPU metric unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** Parses one "util,used,total" line of nvidia-smi CSV output. */
    static Gpu parseNvidia(String output) {
        if (output == null || output.isBlank()) return null;
        String[] parts = output.split(",");
        if (parts.length < 3) return null;
        try {
            double util = Double.parseDouble(parts[0].trim());
            long used = (long) Double.parseDouble(parts[1].trim());
            long total = (long) Double.parseDouble(parts[2].trim());
            return new Gpu(Math.round(util * 10) / 10.0, used, total);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Records the current VRAM and GPU utilization samples (one per interval). */
    private void recordSamples(Gpu gpu) {
        if (gpu == null) return;
        Instant now = Instant.now();
        if (lastSampleAt != null
                && now.isBefore(lastSampleAt.plusSeconds(SAMPLE_INTERVAL_SECONDS))) {
            return; // respect the sampling interval — no per-request spam
        }
        lastSampleAt = now;
        long cutoff = now.toEpochMilli() - HISTORY_WINDOW_SECONDS * 1000;
        if (gpu.vramUsedMb() > 0) {
            synchronized (vramHistory) {
                vramHistory.addLast(new VramSample(now.toEpochMilli(), gpu.vramUsedMb()));
                while (!vramHistory.isEmpty() && vramHistory.peekFirst().timestampMs() < cutoff) {
                    vramHistory.removeFirst();
                }
            }
        }
        synchronized (gpuHistory) {
            gpuHistory.addLast(new GpuSample(now.toEpochMilli(), gpu.utilizationPercent()));
            while (!gpuHistory.isEmpty() && gpuHistory.peekFirst().timestampMs() < cutoff) {
                gpuHistory.removeFirst();
            }
        }
    }

    /** CPU/RAM/GPU snapshot (percent as 0-100, RAM in MB). */
    public record Metrics(Double cpuPercent, Ram ram, Gpu gpu) {
        public boolean gpuAvailable() { return gpu != null; }
    }

    /** RAM usage in MB. */
    public record Ram(long totalMb, long usedMb) {
        public long freeMb() { return Math.max(0, totalMb - usedMb); }
        public double usedPercent() {
            return totalMb > 0 ? Math.round(usedMb * 1000.0 / totalMb) / 10.0 : 0;
        }
    }

    /** GPU utilization percent + VRAM usage in MB. */
    public record Gpu(double utilizationPercent, long vramUsedMb, long vramTotalMb) {
        public double vramPercent() {
            return vramTotalMb > 0 ? Math.round(vramUsedMb * 1000.0 / vramTotalMb) / 10.0 : 0;
        }
    }

    /** One collected VRAM history point. */
    public record VramSample(long timestampMs, long vramUsedMb) {}

    /** One collected GPU utilization history point. */
    public record GpuSample(long timestampMs, double utilizationPercent) {}
}
