package com.example.toilet;

import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "slow.query.threshold.ms=100"
        }
)
@Import(SlowQueryTestConfig.class)
@Slf4j
class RatingAggComparisonTest {
    private static final int RUNS = 20;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ToiletService toiletService;

    @Test
    void measureThreeCases() {
        long[] perToiletNoCache = new long[RUNS];
        long[] perToiletNoCacheTotal = new long[RUNS];
        long[] perToiletNoCacheAgg = new long[RUNS];
        long[] groupNoCache = new long[RUNS];
        long[] groupNoCacheTotal = new long[RUNS];
        long[] groupNoCacheAgg = new long[RUNS];
        long[] groupWithCacheWarm = new long[RUNS];
        long[] groupWithCacheWarmTotal = new long[RUNS];
        long[] groupWithCacheWarmAgg = new long[RUNS];

        for (int i = 0; i < RUNS; i++) {
            Timing t1 = measureOnce(false, "per_toilet", true);
            perToiletNoCache[i] = t1.testMs;
            perToiletNoCacheTotal[i] = t1.totalMs;
            perToiletNoCacheAgg[i] = t1.aggMs;
            logRun("PER_TOILET", i, t1);

            Timing t2 = measureOnce(false, "group", true);
            groupNoCache[i] = t2.testMs;
            groupNoCacheTotal[i] = t2.totalMs;
            groupNoCacheAgg[i] = t2.aggMs;
            logRun("GROUP", i, t2);

            Timing t3 = measureGroupWithCacheWarm();
            groupWithCacheWarm[i] = t3.testMs;
            groupWithCacheWarmTotal[i] = t3.totalMs;
            groupWithCacheWarmAgg[i] = t3.aggMs;
            logRun("GROUP_WARM", i, t3);
        }

        logStats("PER_TOILET", "cache=off", "N+1 queries", perToiletNoCache, perToiletNoCacheTotal, perToiletNoCacheAgg);
        logStats("GROUP", "cache=off", "single GROUP BY", groupNoCache, groupNoCacheTotal, groupNoCacheAgg);
        logStats("GROUP", "cache=on(warm)", "cache hit (0~1 query)", groupWithCacheWarm, groupWithCacheWarmTotal, groupWithCacheWarmAgg);
    }

    private Timing measureOnce(boolean cacheEnabled, String mode, boolean clearCache) {
        configure(cacheEnabled, mode, clearCache);
        long startNanos = System.nanoTime();
        ResponseEntity<String> response = restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        long testMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new Timing(testMs, headerLong(response, "X-Total-Ms"), headerLong(response, "X-Agg-Ms"));
    }

    private Timing measureGroupWithCacheWarm() {
        configure(true, "group", true);
        restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        long startNanos = System.nanoTime();
        ResponseEntity<String> response = restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        long testMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new Timing(testMs, headerLong(response, "X-Total-Ms"), headerLong(response, "X-Agg-Ms"));
    }

    private void configure(boolean cacheEnabled, String mode, boolean clearCache) {
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", cacheEnabled);
        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", mode);
        if (clearCache) {
            Object cache = ReflectionTestUtils.getField(toiletService, "ratingCache");
            if (cache instanceof Map<?, ?> map) {
                map.clear();
            }
        }
    }

    private void logStats(String mode, String cache, String access, long[] testTimes, long[] totalTimes, long[] aggTimes) {
        ModeRange aggRange = modeRange(aggTimes);
        ModeRange totalRange = modeRange(totalTimes);
        ModeRange testRange = modeRange(testTimes);

        log.info("TIMING ({}, {}, access={}, aggMs=modeRange:{}~{}, totalMs=modeRange:{}~{}, testMs=modeRange:{}~{})",
                mode,
                cache,
                access,
                aggRange.min, aggRange.max,
                totalRange.min, totalRange.max,
                testRange.min, testRange.max);
    }

    private void logRun(String label, int index, Timing timing) {
        log.info("RUN({} #{}) aggMs={}, totalMs={}, testMs={}",
                label, index + 1, timing.aggMs, timing.totalMs, timing.testMs);
    }

    private long headerLong(ResponseEntity<String> response, String name) {
        String value = response.getHeaders().getFirst(name);
        if (value == null || value.isBlank()) return -1;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long min(long[] times) {
        long v = Long.MAX_VALUE;
        for (long t : times) v = Math.min(v, t);
        return v;
    }

    private static long max(long[] times) {
        long v = Long.MIN_VALUE;
        for (long t : times) v = Math.max(v, t);
        return v;
    }

    private static ModeRange modeRange(long[] times) {
        if (times.length == 0) {
            return new ModeRange(0, 0);
        }
        java.util.Map<Long, Integer> freq = new java.util.HashMap<>();
        int maxFreq = 0;
        for (long t : times) {
            int next = freq.getOrDefault(t, 0) + 1;
            freq.put(t, next);
            if (next > maxFreq) maxFreq = next;
        }

        long modeMin = Long.MAX_VALUE;
        long modeMax = Long.MIN_VALUE;
        for (var entry : freq.entrySet()) {
            if (entry.getValue() == maxFreq) {
                long v = entry.getKey();
                modeMin = Math.min(modeMin, v);
                modeMax = Math.max(modeMax, v);
            }
        }

        if (modeMin == Long.MAX_VALUE) modeMin = 0;
        if (modeMax == Long.MIN_VALUE) modeMax = 0;

        return new ModeRange(modeMin, modeMax);
    }

    private static long avg(long[] times) {
        long sum = 0;
        for (long t : times) sum += t;
        return times.length == 0 ? 0 : sum / times.length;
    }

    private static long median(long[] times) {
        if (times.length == 0) return 0;
        long[] copy = java.util.Arrays.copyOf(times, times.length);
        java.util.Arrays.sort(copy);
        int mid = copy.length / 2;
        if (copy.length % 2 == 1) {
            return copy[mid];
        }
        return (copy[mid - 1] + copy[mid]) / 2;
    }

    private static final class Timing {
        private final long testMs;
        private final long totalMs;
        private final long aggMs;

        private Timing(long testMs, long totalMs, long aggMs) {
            this.testMs = testMs;
            this.totalMs = totalMs;
            this.aggMs = aggMs;
        }
    }

    private static final class ModeRange {
        private final long min;
        private final long max;

        private ModeRange(long min, long max) {
            this.min = min;
            this.max = max;
        }
    }
}
