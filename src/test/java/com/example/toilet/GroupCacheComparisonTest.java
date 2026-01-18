package com.example.toilet;

import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

@SpringBootTest(properties = {
        "sql.log.enabled=false",
        "sql.log.group-by-only=false",
        "sql.log.count-enabled=false",
        "logging.level.com.example.toilet.service.ToiletService=WARN"
})
@Import(SlowQueryTestConfig.class)
@Slf4j
class GroupCacheComparisonTest {
    private static final int RUNS = 100;
    private static final long LIST_TTL_MS = 10_000L;
    private static final int LONG_SLEEP_RATE_PERCENT = 10;
    private static final long SHORT_SLEEP_MAX_MS = 200L;
    private static final long LONG_SLEEP_MIN_MS = 3_500L;
    private static final long LONG_SLEEP_MAX_MS = 4_500L;
    private static final long RNG_SEED = 20231223L;
    private static final java.util.Random RNG = new java.util.Random(RNG_SEED);

    @Autowired
    private ToiletService toiletService;

    @Test
    void compareGroupCacheOnOff() {
        long[] noCacheListCounts = new long[RUNS];
        long[] noCacheAggCounts = new long[RUNS];
        double[] noCacheAggMs = new double[RUNS];
        double[] noCacheTotalMs = new double[RUNS];
        long[] noCacheDbCounts = new long[RUNS];

        long[] cacheListCounts = new long[RUNS];
        long[] cacheAggCounts = new long[RUNS];
        double[] cacheAggMs = new double[RUNS];
        double[] cacheTotalMs = new double[RUNS];
        long[] cacheDbCounts = new long[RUNS];

        for (int i = 0; i < RUNS; i++) {
            Timing noCache = measureOnce(false, true);
            noCacheListCounts[i] = noCache.listCount;
            noCacheAggCounts[i] = noCache.ratingAggCount;
            noCacheAggMs[i] = noCache.aggMs;
            noCacheTotalMs[i] = noCache.totalMs;
            noCacheDbCounts[i] = noCache.dbCount;
        }

        clearCaches();
        warmCache();
        for (int i = 0; i < RUNS; i++) {
            sleepJitter();
            Timing cached = measureOnce(true, false);
            cacheListCounts[i] = cached.listCount;
            cacheAggCounts[i] = cached.ratingAggCount;
            cacheAggMs[i] = cached.aggMs;
            cacheTotalMs[i] = cached.totalMs;
            cacheDbCounts[i] = cached.dbCount;
        }

        log.info("GROUP_CACHE_CONFIG runs={} ttlMs(list)={} longSleepRate%={} shortSleepMaxMs={} longSleepMs={}~{} seed={}",
                RUNS, LIST_TTL_MS, LONG_SLEEP_RATE_PERCENT,
                SHORT_SLEEP_MAX_MS, LONG_SLEEP_MIN_MS, LONG_SLEEP_MAX_MS, RNG_SEED);
        logSummary("CACHE_OFF", noCacheListCounts, noCacheAggCounts, noCacheDbCounts, noCacheAggMs, noCacheTotalMs);
        logSummary("CACHE_ON", cacheListCounts, cacheAggCounts, cacheDbCounts, cacheAggMs, cacheTotalMs);
    }

    private void warmCache() {
        measureOnce(true, false);
    }

    private Timing measureOnce(boolean cacheEnabled, boolean resetCache) {
        configure(cacheEnabled);
        if (resetCache) {
            clearCaches();
        }
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        toiletService.getAllToiletViews(true);
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        Long aggMs = toiletService.consumeLastAggMs();
        double aggMsValue = aggMs == null ? -1.0 : aggMs.doubleValue();

        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
        long dbCount = SlowQueryTestConfig.getSqlStatementCount();

        return new Timing(totalMs, aggMsValue, listCount, ratingAggCount, dbCount);
    }

    private void configure(boolean cacheEnabled) {
        ReflectionTestUtils.setField(toiletService, "listCacheEnabled", cacheEnabled);
        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");
        ReflectionTestUtils.setField(toiletService, "listCacheTtlMs", LIST_TTL_MS);
    }

    private void clearCaches() {
        ReflectionTestUtils.setField(toiletService, "listCache", null);
        ReflectionTestUtils.setField(toiletService, "listCacheUpdatedMs", 0L);
    }

    private void logSummary(String label,
                            long[] listCounts,
                            long[] ratingAggCounts,
                            long[] dbCounts,
                            double[] aggMsValues,
                            double[] totalMsValues) {
        long listPerReq = perRequestValue(listCounts);
        long aggPerReq = perRequestValue(ratingAggCounts);
        long dbPerReq = perRequestValue(dbCounts);
        Stats aggMsStats = stats(aggMsValues);
        Stats totalMsStats = stats(totalMsValues);

        log.info("GROUP_CACHE_COMPARE ({}, listCount|ratingAggCount|dbCount|aggMs avg|min~P95|totalMs avg|min~P95:{}|{}|{}|{}|{}~{}|{}|{}~{})",
                label,
                listPerReq,
                aggPerReq,
                dbPerReq,
                formatMsDec(aggMsStats.avg), formatMsDec(aggMsStats.min), formatMsDec(aggMsStats.p95),
                formatMsDec(totalMsStats.avg), formatMsDec(totalMsStats.min), formatMsDec(totalMsStats.p95));
    }

    private long perRequestValue(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        return values[0];
    }

    private void sleepJitter() {
        long sleepMs = nextSleepMs();
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long nextSleepMs() {
        int chance = RNG.nextInt(100);
        if (chance < LONG_SLEEP_RATE_PERCENT) {
            return nextLong(LONG_SLEEP_MIN_MS, LONG_SLEEP_MAX_MS + 1);
        }
        return nextLong(0, SHORT_SLEEP_MAX_MS + 1);
    }

    private long nextLong(long origin, long bound) {
        if (bound <= origin) {
            return origin;
        }
        long n = bound - origin;
        return origin + (long) (RNG.nextDouble() * n);
    }

    private Stats stats(double[] values) {
        if (values.length == 0) {
            return new Stats(0, 0, 0);
        }
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        double min = sorted[0];
        double p95 = percentile(sorted, 0.95);
        double avg = avg(sorted);
        return new Stats(avg, min, p95);
    }

    private double percentile(double[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    private double avg(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private static final class Timing {
        private final double totalMs;
        private final double aggMs;
        private final long listCount;
        private final long ratingAggCount;
        private final long dbCount;

        private Timing(double totalMs, double aggMs, long listCount, long ratingAggCount, long dbCount) {
            this.totalMs = totalMs;
            this.aggMs = aggMs;
            this.listCount = listCount;
            this.ratingAggCount = ratingAggCount;
            this.dbCount = dbCount;
        }
    }

    private static final class Stats {
        private final double avg;
        private final double min;
        private final double p95;

        private Stats(double avg, double min, double p95) {
            this.avg = avg;
            this.min = min;
            this.p95 = p95;
        }
    }

    private String formatMsDec(double value) {
        if (value < 0) {
            return "-1";
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
