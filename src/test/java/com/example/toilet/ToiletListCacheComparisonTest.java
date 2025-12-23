package com.example.toilet;

import com.example.toilet.domain.Toilet;
import com.example.toilet.service.ToiletService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sql.log.enabled=false",
                "sql.log.group-by-only=false",
                "sql.log.count-enabled=false",
                "logging.level.root=OFF",
                "list.cache.ttl-ms=1000",
                "spring.test.mockmvc.print=none"
        }
)
@Import({SlowQueryTestConfig.class, ToiletListCacheComparisonTest.CachedToiletController.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ToiletListCacheComparisonTest {
    private static final String URL_NO_CACHE = "/toilets?withRatings=false";
    private static final String URL_CACHED = "/toilets-cached";
    private static final int WARMUP_ITERATIONS = 10;
    private static final int MEASURE_ROUNDS = 1000;
    private static final long LIST_CACHE_TTL_MS = 1_000L;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void compareListTotalMsWithAndWithoutCache() throws Exception {
        clearCachedList();
        warmup();

        PhaseMetrics noCache = new PhaseMetrics("NO_CACHE");
        PhaseMetrics cached = new PhaseMetrics("CACHED");

        for (int round = 1; round <= MEASURE_ROUNDS; round++) {
            runNoCacheOnce(noCache);
            runCachedOnce(cached);
        }

        printSummary(noCache, cached);
    }

    private void warmup() throws Exception {
        for (int i = 1; i <= WARMUP_ITERATIONS; i++) {
            if (i % 2 == 1) {
                runNoCacheOnce(null);
            } else {
                runCachedOnce(null);
            }
        }
    }

    private void runNoCacheOnce(PhaseMetrics metrics) throws Exception {
        SlowQueryTestConfig.resetSqlCounters();
        Timing timing = performRequest(URL_NO_CACHE);
        if (metrics != null) {
            record(metrics, timing);
        }
    }

    private void runCachedOnce(PhaseMetrics metrics) throws Exception {
        SlowQueryTestConfig.resetSqlCounters();
        Timing timing = performRequest(URL_CACHED);
        if (metrics != null) {
            record(metrics, timing);
        }
    }

    private void record(PhaseMetrics metrics, Timing timing) {
        long sample = timing.totalMs;
        metrics.totalMs.add(sample);
        if (timing.cacheHit != null && timing.cacheHit) {
            metrics.hitMs.add(sample);
        } else if (timing.cacheHit != null) {
            metrics.missMs.add(sample);
        }
    }

    private Timing performRequest(String path) throws Exception {
        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(get(path)).andReturn();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        int status = result.getResponse().getStatus();
        if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            Assertions.fail("HTTP " + status + " for " + path);
        }

        String totalHeader = result.getResponse().getHeader("X-Total-Ms");
        String hitHeader = result.getResponse().getHeader("X-Cache-Hit");
        long totalMs = parseHeader(totalHeader, elapsedMs);
        Boolean cacheHit = parseBooleanHeader(hitHeader);
        return new Timing(elapsedMs, totalMs, cacheHit);
    }

    private long parseHeader(String header, long fallback) {
        if (header == null || header.isBlank()) return fallback;
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Boolean parseBooleanHeader(String header) {
        if (header == null || header.isBlank()) return null;
        return Boolean.parseBoolean(header);
    }

    @Autowired
    private CachedToiletController cachedToiletController;

    @RestController
    static class CachedToiletController {
        private final ToiletService toiletService;
        private volatile List<Toilet> cached;
        private volatile long cachedAtMs;

        CachedToiletController(ToiletService toiletService) {
            this.toiletService = toiletService;
        }

        @Value("${list.cache.ttl-ms:0}")
        private long listCacheTtlMs;

        @GetMapping("/toilets-cached")
        public ResponseEntity<List<Toilet>> getToiletsCached() {
            long startNanos = System.nanoTime();
            long now = System.currentTimeMillis();
            List<Toilet> toilets = cached;
            boolean hit = true;
            if (toilets == null || isExpired(now)) {
                toilets = toiletService.getAllToilets();
                cached = toilets;
                cachedAtMs = now;
                hit = false;
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Total-Ms", Long.toString(elapsedMs));
            headers.add("X-Cache-Hit", Boolean.toString(hit));
            return new ResponseEntity<>(toilets, headers, HttpStatus.OK);
        }

        private boolean isExpired(long now) {
            return listCacheTtlMs > 0 && (now - cachedAtMs) >= listCacheTtlMs;
        }

        void clearCache() {
            cached = null;
            cachedAtMs = 0L;
        }
    }

    private void clearCachedList() {
        cachedToiletController.clearCache();
    }

    private void printSummary(PhaseMetrics noCache, PhaseMetrics cached) {
        String header = "runs|avgTotalMs|min~p95ms";
        String combined = String.join(" | ",
                summaryLine(noCache),
                summaryLineCached(cached));
        System.out.println("TIMING_SUMMARY_LIST ttl=" + (LIST_CACHE_TTL_MS / 1000) + " " + header + " " + combined);
    }

    private String summaryLine(PhaseMetrics metrics) {
        Stats totalStats = stats(metrics.totalMs);
        long avgTotal = avgRange(metrics.totalMs, totalStats.min, totalStats.p95);
        long runs = metrics.totalMs.size();
        return String.format("%s:%d|%d|%d~%d",
                metrics.name,
                runs,
                avgTotal,
                totalStats.min,
                totalStats.p95);
    }

    private String summaryLineCached(PhaseMetrics metrics) {
        Stats totalStats = stats(metrics.totalMs);
        Stats hitStats = stats(metrics.hitMs);
        Stats missStats = stats(metrics.missMs);
        long hitMin = hitStats.min >= 0 ? hitStats.min : totalStats.min;
        long missP95 = missStats.p95 >= 0 ? missStats.p95 : totalStats.p95;
        long avgRange = avgRange(metrics.totalMs, hitMin, missP95);
        long runs = metrics.totalMs.size();
        return String.format("%s:%d|%d|%d~%d",
                metrics.name,
                runs,
                avgRange,
                hitMin,
                missP95);
    }

    private long avgRange(List<Long> values, long min, long max) {
        if (values.isEmpty()) return -1;
        long sum = 0;
        long count = 0;
        for (long v : values) {
            if (v >= min && v <= max) {
                sum += v;
                count++;
            }
        }
        if (count == 0) return -1;
        return sum / count;
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
            return new Stats(-1, -1);
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        long min = sorted.get(0);
        long p95 = percentile(sorted, 0.95);
        return new Stats(min, p95);
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
        private final List<Long> hitMs = new ArrayList<>();
        private final List<Long> missMs = new ArrayList<>();

        private PhaseMetrics(String name) {
            this.name = name;
        }
    }

    private static final class Stats {
        private final long min;
        private final long p95;

        private Stats(long min, long p95) {
            this.min = min;
            this.p95 = p95;
        }
    }

    private static final class Timing {
        private final long elapsedMs;
        private final long totalMs;
        private final Boolean cacheHit;

        private Timing(long elapsedMs, long totalMs, Boolean cacheHit) {
            this.elapsedMs = elapsedMs;
            this.totalMs = totalMs;
            this.cacheHit = cacheHit;
        }
    }
}
