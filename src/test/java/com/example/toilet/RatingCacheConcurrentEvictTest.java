package com.example.toilet;

import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "sql.log.enabled=false",
        "sql.log.group-by-only=false",
        "sql.log.count-enabled=false",
        "logging.level.com.example.toilet.service.ToiletService=WARN"
})
@Import(SlowQueryTestConfig.class)
@Slf4j
class RatingCacheConcurrentEvictTest {
    private static final int THREADS = 100;
    private static final long LIST_TTL_MS = 60_000L;
    private static final long RATING_TTL_MS = 60_000L;

    @Autowired
    private ToiletService toiletService;

    @Test
    void compareEvictLockAndRecheckModes() throws Exception {
        configureDefaults();

        long noEvict = runScenario("EVICT_DISABLED", true, true, false);
        long evictOnly = runScenario("EVICT_ONLY", false, false, true);
        long lockOnly = runScenario("EVICT_LOCK", true, false, true);
        long lockRecheck = runScenario("EVICT_LOCK_RECHECK", true, true, true);

        assertTrue(lockRecheck <= lockOnly, "lock recheck should not increase agg queries");
        if (lockOnly > evictOnly) {
            log.warn("LOCK_COMPARE_NOTE lockOnly exceeded evictOnly (lockOnly={}, evictOnly={})", lockOnly, evictOnly);
        }
        assertTrue(noEvict <= lockRecheck, "no-evict should not increase agg queries");
    }

    private long runScenario(String label,
                             boolean lockEnabled,
                             boolean recheckEnabled,
                             boolean evictApplied) throws Exception {
        setLockFlags(lockEnabled, recheckEnabled);
        warmListCache();
        if (evictApplied) {
            clearRatingCache();
            SlowQueryTestConfig.resetSqlCounters();
        } else {
            SlowQueryTestConfig.resetSqlCounters();
            warmRatingCache();
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    toiletService.getAllToiletViews(true);
                    done.countDown();
                    return null;
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        long aggCount = SlowQueryTestConfig.getSqlRatingAggCount();
        long sqlCount = SlowQueryTestConfig.getSqlStatementCount();
        long aggPerReq = perRequest(aggCount);
        long sqlPerReq = perRequest(sqlCount);
        long sqlInFlightMax = SlowQueryTestConfig.getSqlInFlightMax();
        log.info("{} aggCount={} aggPerReq={} sqlCount={} sqlPerReq={} sqlInFlightMax={}",
                label,
                aggCount,
                aggPerReq,
                sqlCount,
                sqlPerReq,
                sqlInFlightMax);
        return aggCount;
    }

    private void configureDefaults() {
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        ReflectionTestUtils.setField(toiletService, "listCacheEnabled", true);
        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");
        ReflectionTestUtils.setField(toiletService, "ratingCacheTtlMs", RATING_TTL_MS);
        ReflectionTestUtils.setField(toiletService, "listCacheTtlMs", LIST_TTL_MS);
    }

    private void setLockFlags(boolean lockEnabled, boolean recheckEnabled) {
        ReflectionTestUtils.setField(toiletService, "ratingCacheLockEnabled", lockEnabled);
        ReflectionTestUtils.setField(toiletService, "ratingCacheLockRecheckEnabled", recheckEnabled);
    }

    private void warmListCache() {
        toiletService.getAllToiletViews(false);
    }

    private void warmRatingCache() {
        toiletService.getAllToiletViews(true);
    }

    @SuppressWarnings("unchecked")
    private void clearRatingCache() {
        Object ratingCache = ReflectionTestUtils.getField(toiletService, "ratingCache");
        if (ratingCache instanceof Map<?, ?> map) {
            ((Map<Long, ?>) map).clear();
        }
    }

    private long perRequest(long totalCount) {
        return THREADS == 0 ? 0L : Math.round((double) totalCount / THREADS);
    }
}
