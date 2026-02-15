package com.example.toilet.service;

import com.example.toilet.domain.Review;
import com.example.toilet.repository.ReviewRepository;
import com.example.toilet.repository.ToiletRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@AllArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {
    private static final Object TX_AFTER_COMMIT_RESOURCE_KEY = new Object();

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
        runAfterCommit(toiletId, true);
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
        runAfterCommit(toiletId, true);
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
        runAfterCommit(toiletId, true);
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
            runAfterCommit(toiletId, true);
            return true;
        }
        return false;
    }

    @Transactional
    public long increaseReviewPageViewCount(Long toiletId) {
        if (toiletId == null) {
            throw new IllegalArgumentException("toiletId is required");
        }
        toiletRepository.incrementReviewPageViewCount(toiletId);
        Long current = toiletRepository.findReviewPageViewCount(toiletId);
        return current == null ? 0L : current;
    }

    public long getReviewPageViewCount(Long toiletId) {
        if (toiletId == null) {
            throw new IllegalArgumentException("toiletId is required");
        }
        Long current = toiletRepository.findReviewPageViewCount(toiletId);
        return current == null ? 0L : current;
    }

    private void runAfterCommit(Long toiletId, boolean evictListCache) {
        if (toiletId == null) return;
        boolean txActive = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
        if (!txActive) {
            if (evictListCache) {
                toiletService.evictListCache();
            }
            ratingSseService.publishRatingUpdateAsync(toiletId);
            return;
        }

        TxAfterCommitActions actions =
                (TxAfterCommitActions) TransactionSynchronizationManager.getResource(TX_AFTER_COMMIT_RESOURCE_KEY);
        if (actions == null) {
            actions = new TxAfterCommitActions();
            TransactionSynchronizationManager.bindResource(TX_AFTER_COMMIT_RESOURCE_KEY, actions);
            TxAfterCommitActions actionsForTx = actions;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (actionsForTx.evictListCache) {
                        toiletService.evictListCache();
                    }
                    for (Long id : actionsForTx.pendingToiletIds) {
                        ratingSseService.publishRatingUpdateAsync(id);
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(TX_AFTER_COMMIT_RESOURCE_KEY)) {
                        TransactionSynchronizationManager.unbindResource(TX_AFTER_COMMIT_RESOURCE_KEY);
                    }
                }
            });
        }

        actions.pendingToiletIds.add(toiletId);
        if (evictListCache) {
            actions.evictListCache = true;
        }
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

    private static final class TxAfterCommitActions {
        private final Set<Long> pendingToiletIds = new LinkedHashSet<>();
        private boolean evictListCache;
    }
}
