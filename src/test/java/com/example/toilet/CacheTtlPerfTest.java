package com.example.toilet;

import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
        "rating.cache.ttl-ms=3000"
})
@Import(SlowQueryTestConfig.class)
@AutoConfigureMockMvc
@Slf4j
class CacheTtlPerfTest {

    private static final String URL = "/toilets?withRatings=true";
    private static final int WARMUP_ITERATIONS = 10;
    private static final int MEASURE_ROUNDS = 50;
    private static final int TTL_SECONDS = 3;
    private static final long TTL_WAIT_MS = (TTL_SECONDS * 1000L) + 200L;
    private static final int TTL_SPIKE_ITERATIONS = 10;

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
            PhaseMetrics ttlSpike = new PhaseMetrics("TTL_EXPIRE_SPIKE");

            // 순서 편향과 JIT/캐시 워밍업 영향을 줄이기 위해 교차 실행한다.
            for (int round = 1; round <= MEASURE_ROUNDS; round++) {
                runNoCacheOnce(noCache, round);
                runWarmOnce(warm, round);
            }

            for (int i = 1; i <= TTL_SPIKE_ITERATIONS; i++) {
                runTtlExpireSpikeOnce(ttlSpike, i);
            }

            printSummary(noCache, warm, ttlSpike);

            if (warm.misses > 0) {
                log.warn("WARM 구간 캐시 미스 발생: {} (TTL 만료 또는 eviction)", warm.misses);
            }

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
        // 워밍업도 교차 실행해 구간별 편차를 줄인다.
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
        Timing timing = performRequest("NO_CACHE", iteration);
        record(metrics, timing, true);
    }

    private void runWarmOnce(PhaseMetrics metrics, int iteration) throws Exception {
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        Timing timing = performRequest("WARM", iteration);
        boolean miss = isCacheMissExpected();
        if (miss) {
            log.warn("TTL_미스_감지 phase=WARM iteration={} aggMs={} totalMs={}",
                    iteration, timing.aggMs, timing.totalMs);
            log.info("WARM 캐시 미스: aggMs={}, totalMs={}", timing.aggMs, timing.totalMs);
        } else {
            log.info("WARM 캐시 히트");
        }
        record(metrics, timing, miss);
    }

    private void runTtlExpireSpikeOnce(PhaseMetrics metrics, int iteration) throws Exception {
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        clearCache();
        // 캐시를 먼저 채운다.
        performRequest("TTL_WARMUP", iteration);
        Thread.sleep(TTL_WAIT_MS);

        Timing timing = performRequest("TTL_EXPIRE_SPIKE", iteration);
        boolean miss = isCacheMissExpected();
        if (miss) {
            log.info("TTL 만료 직 후 재집계 수행 phase=TTL_EXPIRE_SPIKE iteration={} aggMs={} totalMs={}",
                    iteration, timing.aggMs, timing.totalMs);
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

        log.info("실행({} #{}) totalMsHeader={} aggMs={} elapsedMs={}",
                label, iteration, totalHeader, aggHeader, elapsedMs);

        return new Timing(elapsedMs, parseHeader(totalHeader, elapsedMs), parseHeader(aggHeader, -1));
    }

    private void record(PhaseMetrics metrics, Timing timing, boolean miss) {
        if (metrics == null) {
            return;
        }
        metrics.totalMs.add(timing.totalMs);
        if (timing.aggMs >= 0) {
            metrics.aggMs.add(timing.aggMs);
        }
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
            log.warn("TTL_정합성 건너뜀: 화장실 데이터 없음");
            return;
        }

        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        ReflectionTestUtils.setField(toiletService, "ratingCacheTtlMs", TTL_SECONDS * 1000L);
        clearCache();

        AggSnapshot beforeCache = readFromCache(toiletId);
        AggSnapshot beforeDb = readFromDb(toiletId);
        assertClose(beforeCache, beforeDb, "TTL_정합성 before");

        com.example.toilet.domain.Toilet toilet = toiletRepository.findById(toiletId).orElse(null);
        if (toilet == null) {
            log.warn("TTL_정합성 건너뜀: toiletId 없음 id={}", toiletId);
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
        assertClose(afterCache, afterDb, "TTL_정합성 after");

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
        Assertions.assertEquals(db.count, cache.count, label + " count 불일치");
        Assertions.assertEquals(db.avg, cache.avg, 0.0001, label + " avg 불일치");
        log.info("{} 확인: avg={}, count={}", label, cache.avg, cache.count);
    }

    private void printSummary(PhaseMetrics noCache, PhaseMetrics warm, PhaseMetrics ttlSpike) {
        log.info("성능 요약 - TOTAL_MS (ms)");
        log.info(String.format("%-18s %5s %6s %6s %8s %6s %6s",
                "구간", "n", "min", "max", "avg", "p50", "p95"));
        logStats(noCache, false);
        logStats(warm, false);
        logStats(ttlSpike, false);

        log.info("성능 요약 - AGG_MS (ms)");
        log.info(String.format("%-18s %5s %6s %6s %8s %6s %6s",
                "구간", "n", "min", "max", "avg", "p50", "p95"));
        logStats(noCache, true);
        logStats(warm, true);
        logStats(ttlSpike, true);

        log.info("캐시 히트/미스 요약");
        log.info(String.format("%-18s %6s %6s",
                "구간", "hits", "misses"));
        log.info(String.format("%-18s %6d %6d", noCache.name, noCache.hits, noCache.misses));
        log.info(String.format("%-18s %6d %6d", warm.name, warm.hits, warm.misses));
        log.info(String.format("%-18s %6d %6d", ttlSpike.name, ttlSpike.hits, ttlSpike.misses));
    }

    private void logStats(PhaseMetrics metrics, boolean useAggMs) {
        List<Long> values = useAggMs ? metrics.aggMs : metrics.totalMs;
        Stats stats = stats(values);
        log.info(String.format("%-18s %5d %6s %6s %8s %6s %6s",
                metrics.name,
                stats.count,
                format(stats.min),
                format(stats.max),
                formatAvg(stats.avg),
                format(stats.p50),
                format(stats.p95)));
    }

    private String format(long value) {
        return value < 0 ? "n/a" : Long.toString(value);
    }

    private String formatAvg(double value) {
        return value < 0 ? "n/a" : String.format("%.1f", value);
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

        private Timing(long elapsedMs, long totalMs, long aggMs) {
            this.elapsedMs = elapsedMs;
            this.totalMs = totalMs;
            this.aggMs = aggMs;
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

    /*
     * 면접 4줄 요약
     * 1) NO_CACHE와 WARM을 라운드마다 교차 실행해 순서 편향을 줄였습니다.
     * 2) TTL 만료 스파이크를 10회 측정해 분산을 확인했습니다.
     * 3) totalMs와 aggMs를 분리해 응답 시간과 집계 비용을 구분했습니다.
     * 4) 캐시 히트/미스를 같이 출력해 캐시 효과를 수치로 보여줬습니다.
     *
     * 한계 및 향후 개선 아이디어
     * - 단일 인스턴스 기준 측정이므로 실제 분산 환경 지표와 차이가 있음
     * - DB 쿼리 카운트/슬로우 쿼리 로그를 결합해 캐시 미스 원인 분석 강화
     */
}
