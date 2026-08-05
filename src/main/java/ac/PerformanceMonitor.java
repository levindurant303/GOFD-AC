package ac;

import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-allocation rolling performance metrics for the event hot path.
 * Detection samples are recorded in nanoseconds and converted only for reports.
 */
public class PerformanceMonitor {
    private static final int MAX_SAMPLES = 1024;
    private static final int EVENT_WINDOW_SECONDS = 5;

    private final gofd plugin;
    private final long[] detectionSamples = new long[MAX_SAMPLES];
    private final long[] eventBuckets = new long[EVENT_WINDOW_SECONDS];
    private final OperatingSystemMXBean operatingSystem =
            ManagementFactory.getOperatingSystemMXBean();

    private int sampleCount;
    private int sampleCursor;
    private long sampleTotalNanos;
    private long maxDetectionNanos;
    private long currentSecond = System.currentTimeMillis() / 1000L;
    private BukkitTask monitorTask;

    PerformanceMonitor(gofd plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (monitorTask != null || !plugin.isPerformanceMonitoringEnabled()) {
            return;
        }
        monitorTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, (Runnable) this::rotateEventWindow, 20L, 20L);
    }

    void stop() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }

    public synchronized void recordDetectionTime(long nanos) {
        if (nanos < 0L) {
            return;
        }
        long evicted = 0L;
        if (sampleCount == MAX_SAMPLES) {
            evicted = detectionSamples[sampleCursor];
            sampleTotalNanos -= evicted;
        } else {
            sampleCount++;
        }
        detectionSamples[sampleCursor] = nanos;
        sampleTotalNanos += nanos;
        maxDetectionNanos = Math.max(maxDetectionNanos, nanos);
        if (evicted == maxDetectionNanos) {
            maxDetectionNanos = 0L;
            for (int i = 0; i < sampleCount; i++) {
                maxDetectionNanos = Math.max(maxDetectionNanos, detectionSamples[i]);
            }
        }
        sampleCursor = (sampleCursor + 1) % MAX_SAMPLES;
        plugin.recordPerformance("last_detection_nanos", nanos);
    }

    public void recordEvent() {
        synchronized (this) {
            rotateEventWindow(System.currentTimeMillis() / 1000L);
            eventBuckets[(int) (currentSecond % EVENT_WINDOW_SECONDS)]++;
        }
    }

    public synchronized double getAverageDetectionTime() {
        return sampleCount == 0 ? 0.0D : (sampleTotalNanos / (double) sampleCount) / 1_000_000.0D;
    }

    public synchronized long getMaxDetectionTime() {
        return Math.round(maxDetectionNanos / 1_000_000.0D);
    }

    public synchronized double getEventsPerSecond() {
        rotateEventWindow(System.currentTimeMillis() / 1000L);
        long count = 0L;
        for (long bucket : eventBuckets) {
            count += bucket;
        }
        return count / (double) EVENT_WINDOW_SECONDS;
    }

    public double getCPUUsage() {
        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean sun) {
            double processLoad = sun.getProcessCpuLoad();
            if (processLoad >= 0.0D) {
                return processLoad * 100.0D;
            }
        }
        double systemLoad = operatingSystem.getSystemLoadAverage();
        return systemLoad < 0.0D
                ? 0.0D
                : Math.min(100.0D, systemLoad * 100.0D / Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    private synchronized void rotateEventWindow() {
        rotateEventWindow(System.currentTimeMillis() / 1000L);
    }

    private void rotateEventWindow(long second) {
        if (second <= currentSecond) {
            return;
        }
        long steps = Math.min(EVENT_WINDOW_SECONDS, second - currentSecond);
        for (long i = 0; i < steps; i++) {
            currentSecond++;
            eventBuckets[(int) (currentSecond % EVENT_WINDOW_SECONDS)] = 0L;
        }
    }

    public synchronized String generatePerformanceReport() {
        return String.format(
                "性能报告: 平均延迟=%.2fms, 最大延迟=%dms, 事件/秒=%.1f, CPU=%.1f%%",
                getAverageDetectionTime(),
                getMaxDetectionTime(),
                getEventsPerSecond(),
                getCPUUsage()
        );
    }

    public boolean isPerformanceNormal() {
        return getAverageDetectionTime() < 10.0D && getEventsPerSecond() < 1000.0D;
    }

    public List<String> getPerformanceWarnings() {
        List<String> warnings = new ArrayList<>(3);
        double average = getAverageDetectionTime();
        if (average > 10.0D) {
            warnings.add("高检测延迟: " + String.format("%.2f", average) + "ms");
        }
        double events = getEventsPerSecond();
        if (events > 1000.0D) {
            warnings.add("高事件频率: " + String.format("%.1f", events) + " 事件/秒");
        }
        double cpu = getCPUUsage();
        if (cpu > 80.0D) {
            warnings.add("高CPU使用率: " + String.format("%.1f", cpu) + "%");
        }
        return warnings;
    }
}
