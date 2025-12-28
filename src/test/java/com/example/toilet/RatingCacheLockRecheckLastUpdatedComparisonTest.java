package com.example.toilet;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
class RatingCacheLockRecheckLastUpdatedComparisonTest {
    private static final int RUNS = 10;
    private static final int THREADS = 8;
    private static final int PAIRS_PER_RUN = 300;

    @Test
    void compareLastUpdatedGuardWithConcurrentOutOfOrderUpdates() throws Exception {
        ScenarioResult before = runScenario("EVICT_LOCK_RECHECK_LAST_UPDATED_OFF", false);
        ScenarioResult after = runScenario("EVICT_LOCK_RECHECK_LAST_UPDATED_ON", true);

        log.info("LAST_UPDATED_GUARD_RECHECK_SUMMARY runs={} pairsPerRun={} beforeRegressionsTotal={} afterRegressionsTotal={}",
                RUNS, PAIRS_PER_RUN, before.totalRegressions, after.totalRegressions);
    }

    private ScenarioResult runScenario(String label, boolean lastUpdatedGuard) throws Exception {
        long[] elapsedMs = new long[RUNS];
        long[] regressions = new long[RUNS];

        for (int run = 0; run < RUNS; run++) {
            CacheEngine cache = new CacheEngine(lastUpdatedGuard, true);
            cache.evictAll();

            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(PAIRS_PER_RUN * 2);
            LongAdder regressionCounter = new LongAdder();
            long startNs = System.nanoTime();

            try {
                for (int i = 0; i < PAIRS_PER_RUN; i++) {
                    long id = i;
                    long baseTs = System.currentTimeMillis();
                    long newerTs = baseTs + 2;
                    long olderTs = baseTs + 1;

                    pool.submit(() -> {
                        await(start);
                        sleepJitter(0, 1);
                        if (cache.update(id, newerTs, regressionCounter)) {
                            regressionCounter.increment();
                        }
                        done.countDown();
                    });

                    pool.submit(() -> {
                        await(start);
                        sleepJitter(1, 3);
                        if (cache.update(id, olderTs, regressionCounter)) {
                            regressionCounter.increment();
                        }
                        done.countDown();
                    });
                }

                start.countDown();
                done.await(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }

            elapsedMs[run] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            regressions[run] = regressionCounter.sum();
        }

        Stats stats = stats(elapsedMs);
        long totalRegressions = sum(regressions);
        long avgRegressions = RUNS == 0 ? 0 : totalRegressions / RUNS;

        log.info("LAST_UPDATED_GUARD_RECHECK_STATS label={} runs={} pairsPerRun={} regressionsTotal={} regressionsAvgPerRun={} elapsedMs min~p95(avgMs)={}~{}({}) | p99={}",
                label,
                RUNS,
                PAIRS_PER_RUN,
                totalRegressions,
                avgRegressions,
                stats.min,
                stats.p95,
                stats.avg,
                stats.p99);

        return new ScenarioResult(totalRegressions);
    }

    private void sleepJitter(int minMs, int maxMs) {
        if (maxMs <= 0) return;
        int delay = ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
        if (delay <= 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Stats stats(long[] values) {
        if (values.length == 0) {
            return new Stats(0, 0, 0, 0);
        }
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        long min = sorted[0];
        long p95 = percentile(sorted, 0.95);
        long p99 = percentile(sorted, 0.99);
        long avg = avg(sorted);
        return new Stats(avg, min, p95, p99);
    }

    private long percentile(long[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    private long avg(long[] values) {
        long sum = 0L;
        for (long v : values) {
            sum += v;
        }
        return values.length == 0 ? 0L : sum / values.length;
    }

    private long sum(long[] values) {
        long total = 0L;
        for (long v : values) {
            total += v;
        }
        return total;
    }

    private record ScenarioResult(long totalRegressions) {
    }

    private record Stats(long avg, long min, long p95, long p99) {
    }

    private static final class CacheEngine {
        private final Map<Long, RatingAgg> map = new HashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final boolean lastUpdatedGuard;
        private final boolean lockRecheck;

        private CacheEngine(boolean lastUpdatedGuard, boolean lockRecheck) {
            this.lastUpdatedGuard = lastUpdatedGuard;
            this.lockRecheck = lockRecheck;
        }

        boolean update(long id, long nowMs, LongAdder regressionCounter) {
            lock.lock();
            try {
                RatingAgg existing = map.get(id);
                if (lockRecheck) {
                    existing = map.get(id);
                }
                if (existing != null && existing.lastUpdatedMs() > nowMs) {
                    if (!lastUpdatedGuard) {
                        map.put(id, new RatingAgg(1.0, 1L, nowMs));
                        return true;
                    }
                    return false;
                }
                if (existing != null && lastUpdatedGuard && existing.lastUpdatedMs() >= nowMs) {
                    return false;
                }
                map.put(id, new RatingAgg(1.0, 1L, nowMs));
                return false;
            } finally {
                lock.unlock();
            }
        }

        void evictAll() {
            lock.lock();
            try {
                map.clear();
            } finally {
                lock.unlock();
            }
        }
    }

    private record RatingAgg(double sum, long count, long lastUpdatedMs) {
    }
}
