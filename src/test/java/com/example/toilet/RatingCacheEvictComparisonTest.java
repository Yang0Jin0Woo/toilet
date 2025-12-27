package com.example.toilet;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.dto.ToiletView;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import com.example.toilet.service.ReviewService;
import com.example.toilet.service.ToiletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "sql.log.enabled=false",
        "sql.log.group-by-only=false",
        "sql.log.count-enabled=false",
        "logging.level.com.example.toilet.service.ToiletService=WARN"
})
@Import(SlowQueryTestConfig.class)
class RatingCacheEvictComparisonTest {

    private static final long RATING_TTL_MS = 60_000L;
    private static final long LIST_TTL_MS = 60_000L;
    private static final int RUNS = 100;

    @Autowired
    private ToiletService toiletService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @BeforeEach
    void setUp() {
        configureCache();
        clearCaches();
        SlowQueryTestConfig.resetSqlCounters();
    }

    @Test
    void evictStrategyReaggregatesAfterUpdate() {
        Toilet toilet = seedToilet();
        Review r1 = seedReview(toilet, 2, "old");
        seedReview(toilet, 4, "keep");

        warmRatingsCache();

        int hits = 0;
        int misses = 0;
        long aggCountTotal = 0;
        double avgSum = 0.0;
        long reviewCountSum = 0;
        int currentRating = 2;
        for (int i = 1; i <= RUNS; i++) {
            int newRating = nextRating(i);
            reviewService.update(r1.getId(), newRating, "updated-" + i);
            currentRating = newRating;

            SlowQueryTestConfig.resetSqlCounters();
            ToiletView view = findView(toilet.getId());

            long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
            aggCountTotal += ratingAggCount;
            avgSum += view.avgRating();
            reviewCountSum += view.reviewCount();
            if (ratingAggCount == 0) {
                hits++;
            } else {
                misses++;
            }
            assertTrue(ratingAggCount > 0, "evict should trigger re-aggregation on next read");
            assertEquals(expectedAvg(currentRating), view.avgRating(), 0.0001);
            assertEquals(2L, view.reviewCount());
        }
        logSummary("EVICT", hits, misses, aggCountTotal, avgSum, reviewCountSum);
    }

    @Test
    void deltaStrategyAvoidsReaggregationAfterUpdate() {
        Toilet toilet = seedToilet();
        Review r1 = seedReview(toilet, 2, "old");
        seedReview(toilet, 4, "keep");

        warmRatingsCache();

        int hits = 0;
        int misses = 0;
        long aggCountTotal = 0;
        double avgSum = 0.0;
        long reviewCountSum = 0;
        int currentRating = 2;
        for (int i = 1; i <= RUNS; i++) {
            Review target = reviewRepository.findById(r1.getId())
                    .orElseThrow(() -> new IllegalStateException("review not found"));
            int oldRating = target.getRating();
            int newRating = nextRating(i);
            target.setRating(newRating);
            reviewRepository.save(target);

            // Simulate pre-evict behavior by applying cache delta directly.
            toiletService.applyReviewUpdate(toilet.getId(), oldRating, newRating);
            currentRating = newRating;

            SlowQueryTestConfig.resetSqlCounters();
            ToiletView view = findView(toilet.getId());

            long ratingAggCount = SlowQueryTestConfig.getSqlRatingAggCount();
            aggCountTotal += ratingAggCount;
            avgSum += view.avgRating();
            reviewCountSum += view.reviewCount();
            if (ratingAggCount == 0) {
                hits++;
            } else {
                misses++;
            }
            assertEquals(0L, ratingAggCount, "delta update should keep cache hot");
            assertEquals(expectedAvg(currentRating), view.avgRating(), 0.0001);
            assertEquals(2L, view.reviewCount());
        }
        logSummary("DELTA", hits, misses, aggCountTotal, avgSum, reviewCountSum);
    }

    @Test
    void deltaStrategyCanBeOverwrittenByStaleRefresh() {
        Toilet toilet = seedToilet();
        Review r1 = seedReview(toilet, 2, "old");
        seedReview(toilet, 4, "keep");

        warmRatingsCache();

        ReviewRepository.SingleRatingAgg stale = reviewRepository.aggregateByToiletId(toilet.getId());
        double staleAvg = stale.getAvg() == null ? 0.0 : stale.getAvg();
        long staleCnt = stale.getCnt() == null ? 0L : stale.getCnt();

        Review target = reviewRepository.findById(r1.getId())
                .orElseThrow(() -> new IllegalStateException("review not found"));
        int oldRating = target.getRating();
        int newRating = 5;
        target.setRating(newRating);
        reviewRepository.save(target);

        // Simulate pre-evict behavior by applying cache delta directly.
        toiletService.applyReviewUpdate(toilet.getId(), oldRating, newRating);

        // Simulate a stale refresh overwriting the newer cache value.
        long now = System.currentTimeMillis();
        getRatingCache().put(
                toilet.getId(),
                new ToiletService.RatingAgg(staleAvg * staleCnt, staleCnt, now)
        );

        ToiletView view = findView(toilet.getId());
        double expectedNewAvg = expectedAvg(newRating);
        System.out.println(String.format(
                "CACHE_RACE_EVIDENCE staleAvg=%.2f expectedNewAvg=%.2f actualAvg=%.2f",
                staleAvg, expectedNewAvg, view.avgRating()
        ));
        assertEquals(staleAvg, view.avgRating(), 0.0001);
        assertTrue(Math.abs(view.avgRating() - expectedNewAvg) > 0.1);
    }

    private ToiletView findView(Long toiletId) {
        return toiletService.getAllToiletViews(true).stream()
                .filter(v -> v.id().equals(toiletId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("toilet view not found"));
    }

    private void warmRatingsCache() {
        toiletService.getAllToiletViews(true);
    }

    private void configureCache() {
        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        ReflectionTestUtils.setField(toiletService, "listCacheEnabled", false);
        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");
        ReflectionTestUtils.setField(toiletService, "ratingCacheTtlMs", RATING_TTL_MS);
        ReflectionTestUtils.setField(toiletService, "listCacheTtlMs", LIST_TTL_MS);
    }

    @SuppressWarnings("unchecked")
    private void clearCaches() {
        Object ratingCache = ReflectionTestUtils.getField(toiletService, "ratingCache");
        if (ratingCache instanceof Map<?, ?> map) {
            ((Map<Long, ?>) map).clear();
        }
        ReflectionTestUtils.setField(toiletService, "listCache", null);
        ReflectionTestUtils.setField(toiletService, "listCacheUpdatedMs", 0L);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ToiletService.RatingAgg> getRatingCache() {
        Object ratingCache = ReflectionTestUtils.getField(toiletService, "ratingCache");
        if (ratingCache instanceof Map<?, ?> map) {
            return (Map<Long, ToiletService.RatingAgg>) map;
        }
        throw new IllegalStateException("rating cache not found");
    }

    private Toilet seedToilet() {
        Toilet t = new Toilet();
        t.setContsName("Test Toilet");
        t.setAddrNew("Test Address");
        t.setAddrOld("Test Address Old");
        t.setCoordX(127.0);
        t.setCoordY(37.0);
        t.setValue04("M");
        t.setValue05("F");
        t.setExternalId("TEST-" + UUID.randomUUID());
        return toiletRepository.save(t);
    }

    private Review seedReview(Toilet toilet, int rating, String comment) {
        Review r = new Review();
        r.setToilet(toilet);
        r.setRating(rating);
        r.setComment(comment);
        return reviewRepository.save(r);
    }

    private int nextRating(int run) {
        int[] values = {5, 3, 1, 4, 2};
        return values[(run - 1) % values.length];
    }

    private double expectedAvg(int currentRating) {
        return (currentRating + 4) / 2.0;
    }

    private void logSummary(String label,
                            int hits,
                            int misses,
                            long aggCountTotal,
                            double avgSum,
                            long reviewCountSum) {
        double avgRatingAvg = RUNS == 0 ? 0.0 : avgSum / RUNS;
        double reviewCountAvg = RUNS == 0 ? 0.0 : (double) reviewCountSum / RUNS;
        System.out.println(String.format(
                "CACHE_SUMMARY[%s] runs=%d aggCountTotal=%d hits=%d misses=%d avgRatingAvg=%.2f reviewCountAvg=%.2f",
                label, RUNS, aggCountTotal, hits, misses, avgRatingAvg, reviewCountAvg
        ));
    }
}
