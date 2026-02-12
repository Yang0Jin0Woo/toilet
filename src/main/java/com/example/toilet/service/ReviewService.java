package com.example.toilet.service;

import com.example.toilet.domain.Review;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
@AllArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ToiletRepository toiletRepository;
    private final ToiletService toiletService;
    private final RatingSseService ratingSseService;

    public List<Review> findByToilet(Long toiletId) {
        return reviewRepository.findActiveByToiletIdOrderByIdDesc(toiletId);
    }

    public double averageForToilet(Long toiletId) {
        var agg = reviewRepository.aggregateByToiletId(toiletId);
        if (agg == null || agg.getAvg() == null) {
            return 0.0;
        }
        return agg.getAvg();
    }

    @Transactional
    public Review save(Review r) {
        if (r == null || r.getToilet() == null || r.getToilet().getId() == null) {
            throw new IllegalArgumentException("toiletId is required");
        }
        if (r.getRating() == null) {
            throw new IllegalArgumentException("rating is required");
        }
        Long toiletId = r.getToilet().getId();
        Review saved = reviewRepository.save(r);
        recalculateToiletAggregate(toiletId);
        toiletService.evictListCache();
        publishAfterCommit(toiletId);
        return saved;
    }


    @Transactional
    public Review update(Long reviewId, int newRating, String newComment) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));
        Long toiletId = review.getToilet() == null ? null : review.getToilet().getId();
        if (toiletId == null) {
            throw new IllegalStateException("toiletId is required for reviewId: " + reviewId);
        }
        review.setRating(newRating);
        review.setComment(newComment);
        Review saved = reviewRepository.save(review);
        recalculateToiletAggregate(toiletId);
        toiletService.evictListCache();
        publishAfterCommit(toiletId);
        return saved;
    }

    @Transactional
    public void delete(Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));
        Long toiletId = review.getToilet() == null ? null : review.getToilet().getId();
        if (toiletId == null) {
            throw new IllegalStateException("toiletId is required for reviewId: " + reviewId);
        }
        reviewRepository.delete(review);
        recalculateToiletAggregate(toiletId);
        toiletService.evictListCache();
        publishAfterCommit(toiletId);
    }

    @Transactional
    public boolean report(Long reviewId, int blockThreshold) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid reviewId: " + reviewId));
        Long toiletId = review.getToilet() == null ? null : review.getToilet().getId();
        if (toiletId == null) {
            throw new IllegalStateException("toiletId is required for reviewId: " + reviewId);
        }
        int updated = reviewRepository.incrementReportCount(reviewId);
        if (updated == 0) {
            throw new IllegalArgumentException("Invalid reviewId: " + reviewId);
        }
        int deleted = reviewRepository.deleteIfReportCountGte(reviewId, blockThreshold);
        if (deleted > 0) {
            recalculateToiletAggregate(toiletId);
            toiletService.evictListCache();
            publishAfterCommit(toiletId);
            return true;
        }
        return false;
    }

    private void publishAfterCommit(Long toiletId) {
        if (toiletId == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ratingSseService.publishRatingUpdateAsync(toiletId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ratingSseService.publishRatingUpdateAsync(toiletId);
            }
        });
    }

    private void recalculateToiletAggregate(Long toiletId) {
        if (toiletId == null) {
            throw new IllegalArgumentException("toiletId is required");
        }
        var totals = reviewRepository.aggregateTotalsByToiletId(toiletId);
        long sum = totals == null || totals.getSum() == null ? 0L : totals.getSum();
        long cnt = totals == null || totals.getCnt() == null ? 0L : totals.getCnt();
        int updated = toiletRepository.overwriteRatingAgg(toiletId, sum, cnt);
        if (updated == 0) {
            throw new IllegalStateException("Failed to overwrite toilet rating aggregate: " + toiletId);
        }
    }
}
