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
        for (int i = 1; i <= RUNS; i++) {
            reviewService.update(r1.getId(), 5, "updated-" + i);

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
            assertEquals(4.5, view.avgRating(), 0.0001);
            assertEquals(2L, view.reviewCount());

            // Reset rating to keep expected averages consistent across runs.
            reviewService.update(r1.getId(), 2, "reset-" + i);
        }
        logSummary("EVICT", hits, misses, aggCountTotal, avgSum, reviewCountSum);
    }

    @Test
    void deltaStrategyAvoidsReaggregationAfterUpdate() {
        Toilet toilet = seedToilet();
        Review r1 = seedReview(toilet, 1, "old");
        seedReview(toilet, 3, "keep");

        warmRatingsCache();

        int hits = 0;
        int misses = 0;
        long aggCountTotal = 0;
        double avgSum = 0.0;
        long reviewCountSum = 0;
        for (int i = 1; i <= RUNS; i++) {
            Review target = reviewRepository.findById(r1.getId())
                    .orElseThrow(() -> new IllegalStateException("review not found"));
            int oldRating = target.getRating();
            int newRating = (oldRating == 5 ? 1 : 5);
            target.setRating(newRating);
            reviewRepository.save(target);

            // Simulate pre-evict behavior by applying cache delta directly.
            toiletService.applyReviewUpdate(toilet.getId(), oldRating, newRating);

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
            double expectedAvg = newRating == 5 ? 4.0 : 2.0;
            assertEquals(expectedAvg, view.avgRating(), 0.0001);
            assertEquals(2L, view.reviewCount());
        }
        logSummary("DELTA", hits, misses, aggCountTotal, avgSum, reviewCountSum);
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
