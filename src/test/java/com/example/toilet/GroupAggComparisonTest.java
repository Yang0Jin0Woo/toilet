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
        "rating.cache.enabled=false",
        "list.cache.enabled=false",
        "rating.cache.ttl-ms=0",
        "list.cache.ttl-ms=0",
        "sql.log.enabled=false",
        "sql.log.group-by-only=false",
        "sql.log.count-enabled=false",
        "logging.level.com.example.toilet.service.ToiletService=WARN"
})
@Import(SlowQueryTestConfig.class)
@Slf4j
class GroupAggComparisonTest {
    private static final int RUNS = 100;

    @Autowired
    private ToiletService toiletService;

    @Test
    void compareGroupAggregation() {
        long[] perListCounts = new long[RUNS];
        long[] perAggCounts = new long[RUNS];
        long[] perAggMs = new long[RUNS];
        long[] perTotalMs = new long[RUNS];

        long[] groupListCounts = new long[RUNS];
        long[] groupAggCounts = new long[RUNS];
        long[] groupAggMs = new long[RUNS];
        long[] groupTotalMs = new long[RUNS];

        for (int i = 0; i < RUNS; i++) {
            Timing per = measureOnce("per_toilet");
            perListCounts[i] = per.listCount;
            perAggCounts[i] = per.ratingAggCount;
            perAggMs[i] = per.aggMs;
            perTotalMs[i] = per.totalMs;

            Timing group = measureOnce("group");
            groupListCounts[i] = group.listCount;
            groupAggCounts[i] = group.ratingAggCount;
            groupAggMs[i] = group.aggMs;
            groupTotalMs[i] = group.totalMs;
        }

        logSummary("PER_TOILET", perListCounts, perAggCounts, perAggMs, perTotalMs);
        logSummary("GROUP", groupListCounts, groupAggCounts, groupAggMs, groupTotalMs);
    }

    private Timing measureOnce(String mode) {
        configure(mode);
        SlowQueryTestConfig.resetSqlCounters();

        long start = System.nanoTime();
        toiletService.getAllToiletViews(true);
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        Long aggMs = toiletService.consumeLastAggMs();
        long aggMsValue = aggMs == null ? -1 : aggMs;

        long listCount = SlowQueryTestConfig.getSqlToiletListCount();
        long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();

        return new Timing(totalMs, aggMsValue, listCount, ratingAggCount);
    }

    private void configure(String mode) {
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", false);
        ReflectionTestUtils.setField(toiletService, "listCacheEnabled", false);
        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", mode);
    }

    private void logSummary(String label,
                            long[] listCounts,
                            long[] ratingAggCounts,
                            long[] aggMsValues,
                            long[] totalMsValues) {
        long listPerReq = perRequestValue(listCounts);
        long aggPerReq = perRequestValue(ratingAggCounts);
        Stats aggMsStats = stats(aggMsValues);
        Stats totalMsStats = stats(totalMsValues);

        log.info("GROUP_COMPARE ({}, listCount|ratingAggCount|aggMs avg|min~P95|totalMs avg|min~P95:{}|{}|{}|{}~{}|{}|{}~{})",
                label,
                listPerReq,
                aggPerReq,
                aggMsStats.avg, aggMsStats.min, aggMsStats.p95,
                totalMsStats.avg, totalMsStats.min, totalMsStats.p95);
    }

    private Stats stats(long[] values) {
        if (values.length == 0) {
            return new Stats(0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        long min = sorted[0];
        long p95 = percentile(sorted, 0.95);
        long avg = avg(sorted);
        return new Stats(avg, min, p95);
    }

    private long percentile(long[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    private long avg(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private long perRequestValue(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        return values[0];
    }

    private static final class Timing {
        private final long totalMs;
        private final long aggMs;
        private final long listCount;
        private final long ratingAggCount;

        private Timing(long totalMs, long aggMs, long listCount, long ratingAggCount) {
            this.totalMs = totalMs;
            this.aggMs = aggMs;
            this.listCount = listCount;
            this.ratingAggCount = ratingAggCount;
        }
    }

    private static final class Stats {
        private final long avg;
        private final long min;
        private final long p95;

        private Stats(long avg, long min, long p95) {
            this.avg = avg;
            this.min = min;
            this.p95 = p95;
        }
    }

}
