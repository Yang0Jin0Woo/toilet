package com.example.toilet;

import com.example.toilet.domain.Review;
import com.example.toilet.domain.Toilet;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@SpringBootTest(properties = {
        "rating.cache.enabled=true",
        "rating.aggregation.mode=group",
        "rating.cache.ttl-ms=3000"
})
@Slf4j
class CacheConsistencyMismatchTest {

    @Autowired
    private ToiletService toiletService;

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    private static final int FIXED_REVIEW_COUNT = 5;

    @Test
    void detectCacheMismatchWhenDbUpdatedDirectly() {
        Long toiletId = selectAnyToiletId();
        if (toiletId == null) {
            log.warn("화장실 데이터 없음: 불일치 테스트 건너뜀");
            return;
        }

        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");
        clearCache();

        AggSnapshot cacheBefore = readFromCache(toiletId);

        List<Review> inserted = insertFixedReviewsDirectly(toiletId, FIXED_REVIEW_COUNT);
        AggSnapshot dbAfterInsert = readFromDb(toiletId);
        AggSnapshot cacheAfterInsert = readFromCache(toiletId);

        boolean mismatch = cacheAfterInsert.count != dbAfterInsert.count
                || Math.abs(cacheAfterInsert.avg - dbAfterInsert.avg) > 0.0001;

        log.info("캐시_이전 avg={}, count={}", cacheBefore.avg, cacheBefore.count);
        log.info("DB_삽입후 avg={}, count={}", dbAfterInsert.avg, dbAfterInsert.count);
        log.info("캐시_삽입후 avg={}, count={}", cacheAfterInsert.avg, cacheAfterInsert.count);
        log.info("캐시_불일치_감지={}", mismatch);

        Assertions.assertTrue(mismatch, "직접 DB 변경 후 캐시 불일치가 발생해야 합니다");

        // Optional: evict and refresh to restore consistency for subsequent tests.
        toiletService.evictRating(toiletId);
        AggSnapshot cacheAfterEvict = readFromCache(toiletId);
        Assertions.assertEquals(dbAfterInsert.count, cacheAfterEvict.count, "evict 이후 count 불일치");
        Assertions.assertEquals(dbAfterInsert.avg, cacheAfterEvict.avg, 0.0001, "evict 이후 avg 불일치");

        deleteReviews(inserted);
    }

    private Long selectAnyToiletId() {
        var page = toiletRepository.findAll(PageRequest.of(0, 1));
        if (page.isEmpty()) return null;
        return page.getContent().get(0).getId();
    }

    private List<Review> insertFixedReviewsDirectly(Long toiletId, int count) {
        Toilet toilet = toiletRepository.findById(toiletId).orElse(null);
        if (toilet == null) {
            throw new IllegalStateException("toilet not found: " + toiletId);
        }
        List<Review> reviews = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Review review = new Review();
            review.setToilet(toilet);
            review.setRating(1 + (i % 5));
            review.setComment("CACHE_MISMATCH_TEST_" + (i + 1));
            reviews.add(reviewRepository.save(review));
        }
        return reviews;
    }

    private void deleteReviews(List<Review> reviews) {
        for (Review review : reviews) {
            reviewRepository.deleteById(review.getId());
        }
    }

    private AggSnapshot readFromCache(Long toiletId) {
        var list = toiletService.findAllWithRatings();
        for (var t : list) {
            if (toiletId.equals(t.id())) {
                double avg = t.avgRating();
                long cnt = t.reviewCount();
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

    private void clearCache() {
        Object cache = ReflectionTestUtils.getField(toiletService, "ratingCache");
        if (cache instanceof Map<?, ?> map) {
            map.clear();
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
