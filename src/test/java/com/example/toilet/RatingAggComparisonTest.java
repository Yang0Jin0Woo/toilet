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
                "slow.query.threshold.ms=100",
                "sql.log.enabled=true",
                "sql.log.group-by-only=true",
                "sql.log.count-enabled=false",
                "logging.level.com.example.toilet.service.ToiletService=WARN",
                "logging.level.com.example.toilet.controller.ToiletController=WARN",
                "logging.level.org.springframework.web.servlet.DispatcherServlet=WARN",
                "logging.level.org.apache.catalina.core.ContainerBase=WARN"
        }
)
@Import(SlowQueryTestConfig.class)
@Slf4j
class RatingAggComparisonTest {
    private static final int RUNS = 100;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ToiletService toiletService;

    @Test
    void measureThreeCases() {
        long[] perToiletNoCacheTotal = new long[RUNS];
        long[] perToiletNoCacheAgg = new long[RUNS];
        long[] perToiletNoCacheGroupBy = new long[RUNS];
        long[] perToiletNoCacheSqlEvents = new long[RUNS];
        long[] perToiletNoCacheSqlStatements = new long[RUNS];
        long[] perToiletNoCacheToiletList = new long[RUNS];
        long[] perToiletNoCacheRatingAgg = new long[RUNS];

        long[] groupNoCacheTotal = new long[RUNS];
        long[] groupNoCacheAgg = new long[RUNS];
        long[] groupNoCacheGroupBy = new long[RUNS];
        long[] groupNoCacheSqlEvents = new long[RUNS];
        long[] groupNoCacheSqlStatements = new long[RUNS];
        long[] groupNoCacheToiletList = new long[RUNS];
        long[] groupNoCacheRatingAgg = new long[RUNS];

        long[] groupWithCacheWarmTotal = new long[RUNS];
        long[] groupWithCacheWarmAgg = new long[RUNS];
        long[] groupWithCacheWarmGroupBy = new long[RUNS];
        long[] groupWithCacheWarmSqlEvents = new long[RUNS];
        long[] groupWithCacheWarmSqlStatements = new long[RUNS];
        long[] groupWithCacheWarmToiletList = new long[RUNS];
        long[] groupWithCacheWarmRatingAgg = new long[RUNS];

        for (int i = 0; i < RUNS; i++) {
            Timing t1 = measureOnce(false, "per_toilet", true);
            perToiletNoCacheTotal[i] = t1.totalMs;
            perToiletNoCacheAgg[i] = t1.aggMs;
            perToiletNoCacheGroupBy[i] = t1.groupByCount;
            perToiletNoCacheSqlEvents[i] = t1.sqlEvents;
            perToiletNoCacheSqlStatements[i] = t1.sqlStatements;
            perToiletNoCacheToiletList[i] = t1.toiletListCount;
            perToiletNoCacheRatingAgg[i] = t1.ratingAggCount;
            // Per-run logging removed; summary only.

            Timing t2 = measureOnce(false, "group", true);
            groupNoCacheTotal[i] = t2.totalMs;
            groupNoCacheAgg[i] = t2.aggMs;
            groupNoCacheGroupBy[i] = t2.groupByCount;
            groupNoCacheSqlEvents[i] = t2.sqlEvents;
            groupNoCacheSqlStatements[i] = t2.sqlStatements;
            groupNoCacheToiletList[i] = t2.toiletListCount;
            groupNoCacheRatingAgg[i] = t2.ratingAggCount;
            // Per-run logging removed; summary only.

            Timing t3 = measureGroupWithCacheWarm();
            groupWithCacheWarmTotal[i] = t3.totalMs;
            groupWithCacheWarmAgg[i] = t3.aggMs;
            groupWithCacheWarmGroupBy[i] = t3.groupByCount;
            groupWithCacheWarmSqlEvents[i] = t3.sqlEvents;
            groupWithCacheWarmSqlStatements[i] = t3.sqlStatements;
            groupWithCacheWarmToiletList[i] = t3.toiletListCount;
            groupWithCacheWarmRatingAgg[i] = t3.ratingAggCount;
            // Per-run logging removed; summary only.
        }

        logStats("PER_TOILET", "cache=off", "N+1 queries",
                perToiletNoCacheTotal, perToiletNoCacheAgg,
                perToiletNoCacheGroupBy, perToiletNoCacheSqlEvents, perToiletNoCacheSqlStatements,
                perToiletNoCacheToiletList, perToiletNoCacheRatingAgg);
        logStats("GROUP", "cache=off", "single GROUP BY",
                groupNoCacheTotal, groupNoCacheAgg,
                groupNoCacheGroupBy, groupNoCacheSqlEvents, groupNoCacheSqlStatements,
                groupNoCacheToiletList, groupNoCacheRatingAgg);
        logStats("GROUP", "cache=on(warm)", "cache hit (0~1 query)",
                groupWithCacheWarmTotal, groupWithCacheWarmAgg,
                groupWithCacheWarmGroupBy, groupWithCacheWarmSqlEvents, groupWithCacheWarmSqlStatements,
                groupWithCacheWarmToiletList, groupWithCacheWarmRatingAgg);
    }

    private Timing measureOnce(boolean cacheEnabled, String mode, boolean clearCache) {
        configure(cacheEnabled, mode, clearCache);
        SlowQueryTestConfig.resetSqlCounters();
        ResponseEntity<String> response = restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        return new Timing(
                headerLong(response, "X-Total-Ms"),
                headerLong(response, "X-Agg-Ms"),
                SlowQueryTestConfig.getSqlGroupByCount(),
                SlowQueryTestConfig.getSqlCountEvents(),
                SlowQueryTestConfig.getSqlStatementCount(),
                SlowQueryTestConfig.getSqlToiletListCount(),
                SlowQueryTestConfig.getSqlRatingAggCount()
        );
    }

    private Timing measureGroupWithCacheWarm() {
        configure(true, "group", true);
        restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        SlowQueryTestConfig.resetSqlCounters();
        ResponseEntity<String> response = restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        return new Timing(
                headerLong(response, "X-Total-Ms"),
                headerLong(response, "X-Agg-Ms"),
                SlowQueryTestConfig.getSqlGroupByCount(),
                SlowQueryTestConfig.getSqlCountEvents(),
                SlowQueryTestConfig.getSqlStatementCount(),
                SlowQueryTestConfig.getSqlToiletListCount(),
                SlowQueryTestConfig.getSqlRatingAggCount()
        );
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

    private void logStats(String mode, String cache, String access,
                          long[] totalTimes, long[] aggTimes,
                          long[] groupByCounts, long[] sqlEvents, long[] sqlStatements,
                          long[] toiletListCounts, long[] ratingAggCounts) {
        ModeRange aggRange = modeRange(aggTimes);
        ModeRange totalRange = modeRange(totalTimes);
        ModeRange groupByRange = modeRange(groupByCounts);
        ModeRange sqlEventRange = modeRange(sqlEvents);
        ModeRange sqlStatementRange = modeRange(sqlStatements);
        ModeRange toiletListRange = modeRange(toiletListCounts);
        ModeRange ratingAggRange = modeRange(ratingAggCounts);

        long aggP95 = percentile(aggTimes, 0.95);
        long totalP95 = percentile(totalTimes, 0.95);
        long groupByP95 = percentile(groupByCounts, 0.95);
        long sqlEventP95 = percentile(sqlEvents, 0.95);
        long sqlStatementP95 = percentile(sqlStatements, 0.95);
        long toiletListP95 = percentile(toiletListCounts, 0.95);
        long ratingAggP95 = percentile(ratingAggCounts, 0.95);
        long aggAvg = avg(aggTimes);
        long totalAvg = avg(totalTimes);
        long groupByAvg = avg(groupByCounts);
        long sqlEventAvg = avg(sqlEvents);
        long sqlStatementAvg = avg(sqlStatements);
        long toiletListAvg = avg(toiletListCounts);
        long ratingAggAvg = avg(ratingAggCounts);

        log.info("TIMING ({}, {}, access={}, avgAggMs|aggMsMin~P95|avgTotalMs|totalMsMin~P95:{}|{}~{}|{}|{}~{})",
                mode,
                cache,
                access,
                aggAvg, min(aggTimes), aggP95,
                totalAvg, min(totalTimes), totalP95);

        log.info("GROUP_BY_SUMMARY ({}, {}, access={}, avgGroupBy|groupByMin~P95|avgSqlEvents|sqlEventsMin~P95|avgSqlStatements|sqlStatementsMin~P95:{}|{}~{}|{}|{}~{}|{}|{}~{})",
                mode,
                cache,
                access,
                groupByAvg, min(groupByCounts), groupByP95,
                sqlEventAvg, min(sqlEvents), sqlEventP95,
                sqlStatementAvg, min(sqlStatements), sqlStatementP95);

        log.info("SQL_BREAKDOWN ({}, {}, access={}, avgToiletList|toiletListMin~P95|avgRatingAgg|ratingAggMin~P95:{}|{}~{}|{}|{}~{})",
                mode,
                cache,
                access,
                toiletListAvg, min(toiletListCounts), toiletListP95,
                ratingAggAvg, min(ratingAggCounts), ratingAggP95);
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

    private static long percentile(long[] times, double p) {
        if (times.length == 0) return 0;
        long[] copy = java.util.Arrays.copyOf(times, times.length);
        java.util.Arrays.sort(copy);
        int idx = (int) Math.ceil(p * copy.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= copy.length) idx = copy.length - 1;
        return copy[idx];
    }

    private static long avg(long[] times) {
        long sum = 0;
        for (long t : times) sum += t;
        return times.length == 0 ? 0 : sum / times.length;
    }

    private static final class Timing {
        private final long totalMs;
        private final long aggMs;
        private final long groupByCount;
        private final long sqlEvents;
        private final long sqlStatements;
        private final long toiletListCount;
        private final long ratingAggCount;

        private Timing(long totalMs, long aggMs, long groupByCount, long sqlEvents, long sqlStatements,
                       long toiletListCount, long ratingAggCount) {
            this.totalMs = totalMs;
            this.aggMs = aggMs;
            this.groupByCount = groupByCount;
            this.sqlEvents = sqlEvents;
            this.sqlStatements = sqlStatements;
            this.toiletListCount = toiletListCount;
            this.ratingAggCount = ratingAggCount;
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
