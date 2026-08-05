package ac.core;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class DetectionMetricsTest {
    @Test
    public void snapshotsExposeAtomicCounters() {
        DetectionMetrics metrics = new DetectionMetrics();

        metrics.recordDetection("speed");
        metrics.recordDetection("speed");
        metrics.recordDetection("reach");
        metrics.recordFalsePositive();
        metrics.recordPerformance("movement", 42L);

        assertEquals(3L, metrics.totalDetections());
        assertEquals(1L, metrics.falsePositives());
        assertEquals(Map.of("speed", 2L, "reach", 1L), metrics.detectionSnapshot());
        assertEquals(Map.of("movement", 42L), metrics.performanceSnapshot());
    }
}
