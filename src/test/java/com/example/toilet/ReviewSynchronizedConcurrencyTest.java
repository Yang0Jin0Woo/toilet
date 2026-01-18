package com.example.toilet;

import com.example.toilet.domain.Review;
import com.example.toilet.repository.ReviewRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
class ReviewSynchronizedConcurrencyTest {
    private static final int RUNS = 100;
    private static final int THREADS = 8;
    private static final int DELAY_MS = 50;

    @Test
    void compare_update_before_after_synchronized() throws Exception {
        BenchmarkResult before = runUpdateBenchmark(false);
        BenchmarkResult after = runUpdateBenchmark(true);

        log.info("REVIEW_SYNC_COMPARE (UPDATE_BEFORE, runs={}, threads={}, delayMs={}, conflictRuns={}, consistencyFailures={}, finalValueMismatches={})",
                RUNS, THREADS, DELAY_MS, before.conflictRuns, before.consistencyFailures, before.finalValueMismatches);
        log.info("REVIEW_SYNC_COMPARE (UPDATE_AFTER, runs={}, threads={}, delayMs={}, conflictRuns={}, consistencyFailures={}, finalValueMismatches={})",
                RUNS, THREADS, DELAY_MS, after.conflictRuns, after.consistencyFailures, after.finalValueMismatches);

        assertEquals(0, after.conflictRuns, "synchronized update should prevent overlap");
        assertEquals(0, after.consistencyFailures, "synchronized update should keep increments consistent");
        assertEquals(0, after.finalValueMismatches, "synchronized update should keep final value consistent");
    }

    private static void runConcurrent(int threads, Runnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private BenchmarkResult runUpdateBenchmark(boolean synchronizedService) throws Exception {
        int conflictRuns = 0;
        int consistencyFailures = 0;
        int finalValueMismatches = 0;

        for (int i = 0; i < RUNS; i++) {
            AtomicInteger ratingState = new AtomicInteger(0);
            ReviewRepository reviewRepository = mock(ReviewRepository.class);
            when(reviewRepository.findById(1L)).thenAnswer(invocation -> {
                Review review = new Review();
                review.setId(1L);
                review.setRating(ratingState.get());
                return Optional.of(review);
            });
            when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
                Review review = invocation.getArgument(0);
                Integer rating = review.getRating();
                ratingState.set(rating == null ? 0 : rating);
                return review;
            });

            AtomicInteger active = new AtomicInteger();
            AtomicInteger max = new AtomicInteger();
            UpdateRunner updateRunner = synchronizedService
                    ? new SynchronizedUpdateRunner(reviewRepository, active, max)
                    : new UnsynchronizedUpdateRunner(reviewRepository, active, max);

            runConcurrent(THREADS, updateRunner::updateIncrement);

            if (max.get() > 1) {
                conflictRuns++;
            }
            if (ratingState.get() != THREADS) {
                consistencyFailures++;
                finalValueMismatches++;
            }
        }

        return new BenchmarkResult(conflictRuns, consistencyFailures, finalValueMismatches);
    }

    private interface UpdateRunner {
        void updateIncrement();
    }

    private static class UnsynchronizedUpdateRunner implements UpdateRunner {
        private final ReviewRepository reviewRepository;
        private final AtomicInteger active;
        private final AtomicInteger max;

        private UnsynchronizedUpdateRunner(ReviewRepository reviewRepository, AtomicInteger active, AtomicInteger max) {
            this.reviewRepository = reviewRepository;
            this.active = active;
            this.max = max;
        }

        @Override
        public void updateIncrement() {
            int now = active.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            try {
                Review review = reviewRepository.findById(1L)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: 1"));
                int current = review.getRating() == null ? 0 : review.getRating();
                sleepQuietly(DELAY_MS);
                review.setRating(current + 1);
                reviewRepository.save(review);
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private static final class SynchronizedUpdateRunner extends UnsynchronizedUpdateRunner {
        private SynchronizedUpdateRunner(ReviewRepository reviewRepository, AtomicInteger active, AtomicInteger max) {
            super(reviewRepository, active, max);
        }

        @Override
        public synchronized void updateIncrement() {
            super.updateIncrement();
        }
    }

    private static final class BenchmarkResult {
        private final int conflictRuns;
        private final int consistencyFailures;
        private final int finalValueMismatches;

        private BenchmarkResult(int conflictRuns, int consistencyFailures, int finalValueMismatches) {
            this.conflictRuns = conflictRuns;
            this.consistencyFailures = consistencyFailures;
            this.finalValueMismatches = finalValueMismatches;
        }
    }

    private static void sleepQuietly(long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
