package com.example.toilet;

import com.example.toilet.domain.Toilet;
import com.example.toilet.repository.ToiletRepository;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Slf4j
class ReviewPageViewUpdatePerfComparisonTest {
    private static final int RUNS = 100;
    private static final int CONCURRENT_USERS = 1_000;
    private static final int TIMEOUT_SECONDS = 120;

    private static final String RESET_SQL = """
            insert into review_page_view (toilet_id, view_count)
            values (?, 0)
            on duplicate key update view_count = 0
            """;
    private static final String ATOMIC_UPSERT_SQL = """
            insert into review_page_view (toilet_id, view_count)
            values (?, 1)
            on duplicate key update view_count = view_count + 1
            """;
    private static final String SELECT_FOR_UPDATE_SQL = """
            select view_count
            from review_page_view
            where toilet_id = ?
            for update
            """;
    private static final String UPDATE_BY_VALUE_SQL = """
            update review_page_view
            set view_count = ?
            where toilet_id = ?
            """;
    private static final String SELECT_CURRENT_SQL = """
            select view_count
            from review_page_view
            where toilet_id = ?
            """;

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void _1000명동시접근_업데이트전략성능비교() throws Exception {
        Long toiletId = toiletRepository.findAll().stream()
                .findFirst()
                .map(Toilet::getId)
                .orElseThrow(() -> new IllegalStateException("화장실 테이블이 비어있음"));

        warmup(toiletId);

        double[] noControlMs = new double[RUNS];
        double[] atomicMs = new double[RUNS];
        double[] pessimisticMs = new double[RUNS];
        double[] noControlLossRates = new double[RUNS];
        double[] atomicLossRates = new double[RUNS];
        double[] pessimisticLossRates = new double[RUNS];

        long[] noControlFinalCounts = new long[RUNS];
        long[] atomicFinalCounts = new long[RUNS];
        long[] pessimisticFinalCounts = new long[RUNS];
        long expectedCount = CONCURRENT_USERS;

        for (int i = 0; i < RUNS; i++) {
            BatchResult noControl = measureConcurrentBatch(toiletId, this::runNoControlIncrement);
            noControlMs[i] = noControl.elapsedMs;
            noControlFinalCounts[i] = noControl.finalCount;
            noControlLossRates[i] = lostUpdateRate(expectedCount, noControl.finalCount);

            BatchResult atomic = measureConcurrentBatch(toiletId, this::runAtomicIncrement);
            atomicMs[i] = atomic.elapsedMs;
            atomicFinalCounts[i] = atomic.finalCount;
            atomicLossRates[i] = lostUpdateRate(expectedCount, atomic.finalCount);

            BatchResult pessimistic = measureConcurrentBatch(toiletId, this::runPessimisticLockIncrement);
            pessimisticMs[i] = pessimistic.elapsedMs;
            pessimisticFinalCounts[i] = pessimistic.finalCount;
            pessimisticLossRates[i] = lostUpdateRate(expectedCount, pessimistic.finalCount);
        }

        for (long count : atomicFinalCounts) {
            Assertions.assertThat(count).isEqualTo((long) CONCURRENT_USERS);
        }
        for (long count : pessimisticFinalCounts) {
            Assertions.assertThat(count).isEqualTo((long) CONCURRENT_USERS);
        }
        for (double rate : atomicLossRates) {
            Assertions.assertThat(rate).isZero();
        }
        for (double rate : pessimisticLossRates) {
            Assertions.assertThat(rate).isZero();
        }
        Assertions.assertThat(Arrays.stream(noControlLossRates).anyMatch(rate -> rate > 0.0))
                .withFailMessage("동시성 제어 미적용에서 유실 업데이트가 관측되지 않았습니다.")
                .isTrue();

        Stats noControlTime = stats(noControlMs);
        Stats atomicTime = stats(atomicMs);
        Stats pessimisticTime = stats(pessimisticMs);
        Stats noControlLoss = stats(noControlLossRates);
        Stats atomicLoss = stats(atomicLossRates);
        Stats pessimisticLoss = stats(pessimisticLossRates);

        CountStats noControlCount = countStats(noControlFinalCounts);
        CountStats atomicCount = countStats(atomicFinalCounts);
        CountStats pessimisticCount = countStats(pessimisticFinalCounts);

        log.info("조회수 성능 설정: 반복={} 동시 사용자={} 모드=동일 화장실ID toiletId={}",
                RUNS, CONCURRENT_USERS, toiletId);
        log.info("조회수 성능 비교 (동시성 제어 미적용, 총 수행시간 min~P95(평균)= {} ms ~ {} ms ({} ms), 최종 조회수 min~P95(평균)= {} ~ {} ({}), 유실 업데이트율 min~P95(평균)= {} ~ {} ({}))",
                formatMs(noControlTime.min), formatMs(noControlTime.p95), formatMs(noControlTime.avg),
                noControlCount.min, noControlCount.p95, noControlCount.avg,
                formatPercent(noControlLoss.min), formatPercent(noControlLoss.p95), formatPercent(noControlLoss.avg));
        log.info("조회수 성능 비교 (원자 업데이트, 총 수행시간 min~P95(평균)= {} ms ~ {} ms ({} ms), 최종 조회수 min~P95(평균)= {} ~ {} ({}), 유실 업데이트율 min~P95(평균)= {} ~ {} ({}))",
                formatMs(atomicTime.min), formatMs(atomicTime.p95), formatMs(atomicTime.avg),
                atomicCount.min, atomicCount.p95, atomicCount.avg,
                formatPercent(atomicLoss.min), formatPercent(atomicLoss.p95), formatPercent(atomicLoss.avg));
        log.info("조회수 성능 비교 (비관적 락, 총 수행시간 min~P95(평균)= {} ms ~ {} ms ({} ms), 최종 조회수 min~P95(평균)= {} ~ {} ({}), 유실 업데이트율 min~P95(평균)= {} ~ {} ({}))",
                formatMs(pessimisticTime.min), formatMs(pessimisticTime.p95), formatMs(pessimisticTime.avg),
                pessimisticCount.min, pessimisticCount.p95, pessimisticCount.avg,
                formatPercent(pessimisticLoss.min), formatPercent(pessimisticLoss.p95), formatPercent(pessimisticLoss.avg));
    }

    private void warmup(Long toiletId) throws Exception {
        measureConcurrentBatch(toiletId, this::runNoControlIncrement);
        measureConcurrentBatch(toiletId, this::runAtomicIncrement);
        measureConcurrentBatch(toiletId, this::runPessimisticLockIncrement);
    }

    private BatchResult measureConcurrentBatch(Long toiletId, IncrementOperation operation) throws Exception {
        resetViewCount(toiletId);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_USERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_USERS);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < CONCURRENT_USERS; i++) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        operation.apply(toiletId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add(e);
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            boolean allReady = ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Assertions.assertThat(allReady)
                    .withFailMessage("동시 작업 준비 대기 시간 초과")
                    .isTrue();

            long startNs = System.nanoTime();
            start.countDown();
            boolean allDone = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Assertions.assertThat(allDone)
                    .withFailMessage("동시 작업 완료 대기 시간 초과")
                    .isTrue();

            if (!failures.isEmpty()) {
                Throwable sample = failures.peek();
                throw new IllegalStateException("worker failure count=" + failures.size(), sample);
            }

            long finalCount = currentViewCount(toiletId);
            return new BatchResult(elapsedMs(startNs), finalCount);
        } finally {
            executor.shutdown();
            boolean terminated = executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Assertions.assertThat(terminated)
                    .withFailMessage("Executor 종료 대기 시간 초과")
                    .isTrue();
        }
    }

    private void runNoControlIncrement(Long toiletId) {
        Long current = jdbcTemplate.queryForObject(SELECT_CURRENT_SQL, Long.class, toiletId);
        if (current == null) {
            throw new IllegalStateException("view row not found: toiletId=" + toiletId);
        }
        int updated = jdbcTemplate.update(UPDATE_BY_VALUE_SQL, current + 1L, toiletId);
        if (updated <= 0) {
            throw new IllegalStateException("no-control update failed: toiletId=" + toiletId);
        }
    }

    private void runAtomicIncrement(Long toiletId) {
        int updated = jdbcTemplate.update(ATOMIC_UPSERT_SQL, toiletId);
        if (updated <= 0) {
            throw new IllegalStateException("atomic upsert failed: toiletId=" + toiletId);
        }
    }

    private void runPessimisticLockIncrement(Long toiletId) {
        Integer updated = tx.execute(status -> {
            Long current = jdbcTemplate.queryForObject(SELECT_FOR_UPDATE_SQL, Long.class, toiletId);
            if (current == null) {
                throw new IllegalStateException("view row not found: toiletId=" + toiletId);
            }
            return jdbcTemplate.update(UPDATE_BY_VALUE_SQL, current + 1L, toiletId);
        });
        if (updated == null || updated <= 0) {
            throw new IllegalStateException("pessimistic lock update failed: toiletId=" + toiletId);
        }
    }

    private void resetViewCount(Long toiletId) {
        jdbcTemplate.update(RESET_SQL, toiletId);
    }

    private long currentViewCount(Long toiletId) {
        Long value = jdbcTemplate.queryForObject(SELECT_CURRENT_SQL, Long.class, toiletId);
        return value == null ? 0L : value;
    }

    private double elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000.0;
    }

    private Stats stats(double[] values) {
        if (values.length == 0) {
            return new Stats(0, 0, 0);
        }
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        return new Stats(avg(sorted), sorted[0], percentile(sorted, 0.95));
    }

    private CountStats countStats(long[] values) {
        if (values.length == 0) {
            return new CountStats(0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        return new CountStats(avg(sorted), sorted[0], percentile(sorted, 0.95));
    }

    private double percentile(double[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    private long percentile(long[] sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    private double avg(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private long avg(long[] values) {
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private String formatMs(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String formatPercent(double ratio) {
        return String.format(Locale.US, "%.2f%%", ratio * 100.0);
    }

    private double lostUpdateRate(long expectedCount, long finalCount) {
        if (expectedCount <= 0L) {
            return 0.0;
        }
        long lost = expectedCount - finalCount;
        if (lost < 0L) {
            lost = 0L;
        }
        return (double) lost / (double) expectedCount;
    }

    @FunctionalInterface
    private interface IncrementOperation {
        void apply(Long toiletId);
    }

    private static final class BatchResult {
        private final double elapsedMs;
        private final long finalCount;

        private BatchResult(double elapsedMs, long finalCount) {
            this.elapsedMs = elapsedMs;
            this.finalCount = finalCount;
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

    private static final class CountStats {
        private final long avg;
        private final long min;
        private final long p95;

        private CountStats(long avg, long min, long p95) {
            this.avg = avg;
            this.min = min;
            this.p95 = p95;
        }
    }
}
