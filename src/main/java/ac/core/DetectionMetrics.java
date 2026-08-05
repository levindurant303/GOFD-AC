package ac.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free counters shared by event modules and administrative commands. */
public final class DetectionMetrics {
    private final LongAdder totalDetections = new LongAdder();
    private final LongAdder falsePositives = new LongAdder();
    private final ConcurrentMap<String, LongAdder> detections = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> performance = new ConcurrentHashMap<>();

    public void recordDetection(String type) {
        totalDetections.increment();
        detections.computeIfAbsent(type, ignored -> new LongAdder()).increment();
    }

    public void recordFalsePositive() {
        falsePositives.increment();
    }

    public void recordPerformance(String module, long value) {
        performance.computeIfAbsent(module, ignored -> new AtomicLong()).set(value);
    }

    public long totalDetections() {
        return totalDetections.sum();
    }

    public long falsePositives() {
        return falsePositives.sum();
    }

    public Map<String, Long> detectionSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        detections.forEach((key, value) -> snapshot.put(key, value.sum()));
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Long> performanceSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        performance.forEach((key, value) -> snapshot.put(key, value.get()));
        return Collections.unmodifiableMap(snapshot);
    }
}
