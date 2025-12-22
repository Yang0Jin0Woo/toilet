package com.example.toilet;

import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {
        "rating.cache.enabled=true",
        "rating.aggregation.mode=group",
        "rating.cache.ttl-ms=30000",
        "spring.test.mockmvc.print=none",
        "slow.query.threshold.ms=999999",
        "sql.log.enabled=false",
        "logging.level.com.example.toilet.service.ToiletService=WARN",
        "logging.level.com.example.toilet.controller.ToiletController=WARN",
        "logging.level.org.springframework.web.servlet.DispatcherServlet=WARN",
        "logging.level.org.apache.catalina.core.ContainerBase=WARN"
})
@Import(SlowQueryTestConfig.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Slf4j
class CacheTtlPerfTest {

    private static final String URL = "/toilets?withRatings=true";
    private static final int WARMUP_ITERATIONS = 10;
    private static final int MEASURE_ROUNDS = 1000;
    private static final int TTL_SECONDS = 30;
    private static final long TTL_WAIT_MS = (TTL_SECONDS * 1000L) + 200L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ToiletService toiletService;

    @Autowired
    private com.example.toilet.repository.ToiletRepository toiletRepository;

    @Autowired
    private com.example.toilet.repository.ReviewRepository reviewRepository;

    @Test
    void compareCacheTtlPerf() throws Exception {
        Object originalCacheEnabled = ReflectionTestUtils.getField(toiletService, "ratingCacheEnabled");
        Object originalTtl = ReflectionTestUtils.getField(toiletService, "ratingCacheTtlMs");
        Object originalMode = ReflectionTestUtils.getField(toiletService, "ratingAggregationMode");
        

        try {
            ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");
            
            ReflectionTestUtils.setField(toiletService, "ratingCacheTtlMs", TTL_SECONDS * 1000L);

            clearCache();
            warmup();

            PhaseMetrics noCache = new PhaseMetrics("NO_CACHE");
            PhaseMetrics warm = new PhaseMetrics("WARM");

            for (int round = 1; round <= MEASURE_ROUNDS; round++) {
                runNoCacheOnce(noCache, round);
                runWarmOnce(warm, round);
            }

            printSummary(noCache, warm);

            verifyTtlConsistency();
        } finally {
            if (originalCacheEnabled != null) {
                ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", originalCacheEnabled);
            }
            if (originalTtl != null) {
                ReflectionTestUtils.setField(toiletService, "ratingCacheTtlMs", originalTtl);
            }
            if (originalMode != null) {
                ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", originalMode);
            }
        }
    }

    private void warmup() throws Exception {
        for (int i = 1; i <= WARMUP_ITERATIONS; i++) {
            if (i % 2 == 1) {
                runNoCacheOnce(null, i);
            } else {
                runWarmOnce(null, i);
            }
        }
    }

    private void runNoCacheOnce(PhaseMetrics metrics, int iteration) throws Exception {
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", false);
        SlowQueryTestConfig.resetSqlCounters();
        Timing timing = performRequest("NO_CACHE", iteration);
        if (metrics == null) {
            return;
        }
        record(metrics, timing, true);
    }

    private void runWarmOnce(PhaseMetrics metrics, int iteration) throws Exception {
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        SlowQueryTestConfig.resetSqlCounters();
        Timing timing = performRequest("WARM", iteration);
        boolean miss = isCacheMissExpected();
        if (metrics == null) {
            return;
        }
        record(metrics, timing, miss);
    }

    private Timing performRequest(String label, int iteration) throws Exception {
        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(request())
                .andReturn();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        int status = result.getResponse().getStatus();
        if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            Assertions.fail("HTTP " + status + " at " + label + " #" + iteration);
        }

        String totalHeader = result.getResponse().getHeader("X-Total-Ms");
        String aggHeader = result.getResponse().getHeader("X-Agg-Ms");

        return new Timing(
                elapsedMs,
                parseHeader(totalHeader, elapsedMs),
                parseHeader(aggHeader, -1),
                SlowQueryTestConfig.getSqlGroupByCount(),
                SlowQueryTestConfig.getSqlCountEvents(),
                SlowQueryTestConfig.getSqlStatementCount()
        );
    }

    private void record(PhaseMetrics metrics, Timing timing, boolean miss) {
        if (metrics == null) {
            return;
        }
        if (timing.aggMs >= 0) {
            metrics.aggMsAll.add(timing.aggMs);
        }
        if (timing.aggMs > 0) {
            metrics.sqlRuns++;
            metrics.aggMs.add(timing.aggMs);
        }
        metrics.totalMs.add(timing.totalMs);
        metrics.testMs.add(timing.elapsedMs);
        metrics.groupByCounts.add(timing.groupByCount);
        metrics.sqlEvents.add(timing.sqlEvents);
        metrics.sqlStatements.add(timing.sqlStatements);
        if (miss) {
            metrics.misses++;
        } else {
            metrics.hits++;
        }
    }

    private long parseHeader(String header, long fallback) {
        if (header == null || header.isBlank()) return fallback;
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isCacheMissExpected() {
        Object ttlObj = ReflectionTestUtils.getField(toiletService, "ratingCacheTtlMs");
        long ttlMs = ttlObj instanceof Number ? ((Number) ttlObj).longValue() : 0L;
        Map<?, ?> cache = getCache();
        if (cache == null || cache.isEmpty()) {
            return true;
        }
        long now = System.currentTimeMillis();
        for (Object value : cache.values()) {
            if (value instanceof ToiletService.RatingAgg agg) {
                if (agg.isStale(now, ttlMs)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ToiletService.RatingAgg> getCache() {
        Object cache = ReflectionTestUtils.getField(toiletService, "ratingCache");
        if (cache instanceof Map<?, ?> map) {
            return (Map<Long, ToiletService.RatingAgg>) map;
        }
        return null;
    }

    private void clearCache() {
        Map<Long, ToiletService.RatingAgg> cache = getCache();
        if (cache != null) {
            cache.clear();
        }
    }

    private MockHttpServletRequestBuilder request() {
        return get(URL);
    }

    private void verifyTtlConsistency() throws Exception {
        Long toiletId = selectAnyToiletId();
        if (toiletId == null) {
            return;
        }

        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");           
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        ReflectionTestUtils.setField(toiletService, "ratingCacheTtlMs", TTL_SECONDS * 1000L);
        clearCache();

        AggSnapshot beforeCache = readFromCache(toiletId);
        AggSnapshot beforeDb = readFromDb(toiletId);
        assertClose(beforeCache, beforeDb, "TTL_?�합??before");

        com.example.toilet.domain.Toilet toilet = toiletRepository.findById(toiletId).orElse(null);
        if (toilet == null) {
            return;
        }

        com.example.toilet.domain.Review review = new com.example.toilet.domain.Review();
        review.setToilet(toilet);
        review.setRating(5);
        review.setComment("TTL_TEST_REVIEW");
        review = reviewRepository.save(review);

        AggSnapshot afterDb = readFromDb(toiletId);
        Thread.sleep(TTL_WAIT_MS);
        AggSnapshot afterCache = readFromCache(toiletId);
        assertClose(afterCache, afterDb, "TTL_?�합??after");

        reviewRepository.deleteById(review.getId());
    }

    private Long selectAnyToiletId() {
        var page = toiletRepository.findAll(PageRequest.of(0, 1));
        if (page.isEmpty()) return null;
        return page.getContent().get(0).getId();
    }

    private AggSnapshot readFromCache(Long toiletId) {
        var list = toiletService.findAllWithRatings();
        for (var t : list) {
            if (toiletId.equals(t.getId())) {
                double avg = t.getAvgRating() == null ? 0.0 : t.getAvgRating();
                long cnt = t.getReviewCount() == null ? 0L : t.getReviewCount();
                return new AggSnapshot(avg, cnt);
            }
        }
        return new AggSnapshot(0.0, 0L);
    }

    private AggSnapshot readFromDb(Long toiletId) {
        var agg = reviewRepository.aggregateByToiletId(toiletId);
        if (agg == null) return new AggSnapshot(0.0, 0L);
        double avg = agg.getAvg() == null ? 0.0 : agg.getAvg();
        long cnt = agg.getCnt() == null ? 0L : agg.getCnt();
        return new AggSnapshot(avg, cnt);
    }

    private void assertClose(AggSnapshot cache, AggSnapshot db, String label) {
        Assertions.assertEquals(db.count, cache.count, label + " count mismatch");
        Assertions.assertEquals(db.avg, cache.avg, 0.0001, label + " avg mismatch");
    }

    // runs = cache sql request
    private void printSummary(PhaseMetrics noCache, PhaseMetrics warm) {
        String header = "runs|avgAggMsAll|aggMsMin~P95|avgTotalMs|totalMsMin~P95|avgTestMs|testMsMin~P95";
        String combined = String.join(" | ",
                summaryLine(noCache),
                summaryLine(warm));
        log.info("TIMING_SUMMARY_200 ttl={} {} {}", TTL_SECONDS, header, combined);
    }

    private String summaryLine(PhaseMetrics metrics) {
        Stats aggStats = stats(metrics.aggMsAll);
        Stats totalStats = stats(metrics.totalMs);
        Stats testStats = stats(metrics.testMs);
        long avgAggAll = avgLong(metrics.aggMsAll);
        long avgTotal = avgLong(metrics.totalMs);
        long avgTest = avgLong(metrics.testMs);
        long runs = metrics.sqlRuns;
        return String.format("%s:%d|%d|%d~%d|%d|%d~%d|%d|%d~%d",
                metrics.name,
                runs,
                avgAggAll, aggStats.min, aggStats.p95,
                avgTotal, totalStats.min, totalStats.p95,
                avgTest, testStats.min, testStats.p95);
    }

    private long avgLong(List<Long> values) {
        if (values.isEmpty()) {
            return -1;
        }
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private Stats stats(List<Long> values) {
        if (values.isEmpty()) {
            return new Stats(0, -1, -1, -1, -1, -1);
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);

        return new Stats(sorted.size(), min, max, avg, p50, p95);
    }

    private long percentile(List<Long> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    private static final class PhaseMetrics {
        private final String name;
        private final List<Long> totalMs = new ArrayList<>();
        private final List<Long> aggMs = new ArrayList<>();
        private final List<Long> aggMsAll = new ArrayList<>();
        private final List<Long> testMs = new ArrayList<>();
        private final List<Long> groupByCounts = new ArrayList<>();
        private final List<Long> sqlEvents = new ArrayList<>();
        private final List<Long> sqlStatements = new ArrayList<>();
        private long sqlRuns;
        private int hits;
        private int misses;

        private PhaseMetrics(String name) {
            this.name = name;
        }
    }

    private static final class Timing {
        private final long elapsedMs;
        private final long totalMs;
        private final long aggMs;
        private final long groupByCount;
        private final long sqlEvents;
        private final long sqlStatements;

        private Timing(long elapsedMs, long totalMs, long aggMs,
                       long groupByCount, long sqlEvents, long sqlStatements) {
            this.elapsedMs = elapsedMs;
            this.totalMs = totalMs;
            this.aggMs = aggMs;
            this.groupByCount = groupByCount;
            this.sqlEvents = sqlEvents;
            this.sqlStatements = sqlStatements;
        }
    }

    private static final class Stats {
        private final int count;
        private final long min;
        private final long max;
        private final double avg;
        private final long p50;
        private final long p95;

        private Stats(int count, long min, long max, double avg, long p50, long p95) {
            this.count = count;
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.p50 = p50;
            this.p95 = p95;
        }
    }

    private static final class AggSnapshot {
        private final double avg;
        private final long count;

        private AggSnapshot(double avg, long count) {
            this.avg = avg;
            this.count = count;
        }
    }
}























