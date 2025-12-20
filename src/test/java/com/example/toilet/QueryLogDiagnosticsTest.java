package com.example.toilet;

import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "slow.query.threshold.ms=100",
                "sql.log.enabled=true",
                "sql.log.group-by-only=false"
        }
)
@Import(SlowQueryTestConfig.class)
@Slf4j
class QueryLogDiagnosticsTest {

    private static final int NO_CACHE_REQUESTS = 10;
    private static final int WARMUP_REQUESTS = 5;
    private static final int WARM_REQUESTS = 5;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ToiletService toiletService;

    @Test
    void logGroupByPerRequestWithCacheOff() {
        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");
        clearCache();

        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", false);
        for (int i = 1; i <= NO_CACHE_REQUESTS; i++) {
            Timing t = requestOnce("NO_CACHE", i);
            log.info("NO_CACHE #{} aggMs={} totalMs={} testMs={}", i, t.aggMs, t.totalMs, t.testMs);
        }

        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        clearCache();

        for (int i = 1; i <= WARMUP_REQUESTS; i++) {
            Timing warmup = requestOnce("WARMUP", i);
            log.info("WARMUP #{} aggMs={} totalMs={} testMs={}", i, warmup.aggMs, warmup.totalMs, warmup.testMs);
        }
        for (int i = 1; i <= WARM_REQUESTS; i++) {
            Timing warm = requestOnce("WARM", i);
            log.info("WARM #{} aggMs={} totalMs={} testMs={}", i, warm.aggMs, warm.totalMs, warm.testMs);
        }
    }

    private Timing requestOnce(String label, int iteration) {
        long startNanos = System.nanoTime();
        ResponseEntity<String> response = restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        long testMs = (System.nanoTime() - startNanos) / 1_000_000;

        long totalMs = headerLong(response, "X-Total-Ms");
        long aggMs = headerLong(response, "X-Agg-Ms");

        log.info("REQ({} #{}) aggMs={} totalMs={} testMs={}", label, iteration, aggMs, totalMs, testMs);
        return new Timing(testMs, totalMs, aggMs);
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

    @SuppressWarnings("unchecked")
    private void clearCache() {
        Object cache = ReflectionTestUtils.getField(toiletService, "ratingCache");
        if (cache instanceof Map<?, ?> map) {
            map.clear();
        }
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
}
